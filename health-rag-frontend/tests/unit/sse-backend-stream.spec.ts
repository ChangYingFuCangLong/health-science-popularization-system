// @vitest-environment node
/* ============================================================
 * 用【后端真实输出】做回归
 *
 * 夹具来源：2026-09-18 从本机后端 POST /api/chat/stream 抓到的原始字节流
 * （M1 阶段的 MockChatService，reference ×1 + delta ×24 + done ×1）。
 *
 * 为什么要有这个测试：
 *   合成用例只能证明"我的解析器符合我对规范的理解"；
 *   这份夹具能证明"我的解析器符合【后端真实的输出】"。
 *   以后后端改协议（比如冒号后加空格、改成 CRLF、改字段名），这里会立刻红。
 *
 * 后端真实输出的两个特征（与规范允许的形式略有不同，必须兼容）：
 *   1. 换行是 LF（不是 CRLF）
 *   2. `event:` / `data:` 冒号后【没有空格】——SSE 规范允许带一个空格，
 *      Spring 的 ServerSentEvent 选择不带。解析器两种都要能吃。
 * ============================================================ */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { createSSEParser } from '@/utils/sse'

const raw = readFileSync(
  fileURLToPath(new URL('../fixtures/backend-mock-stream.txt', import.meta.url)),
  'utf-8'
)

describe('后端真实 SSE 流回归', () => {
  it('原始夹具本身符合预期（LF 换行、冒号后无空格）', () => {
    expect(raw).toContain('event:reference') // 无空格
    expect(raw).not.toContain('event: reference')
    expect(raw).not.toContain('\r\n') // 纯 LF
  })

  it('能解析出 1 个 reference、24 个 delta、1 个 done', () => {
    const order: string[] = []
    const parser = createSSEParser({
      reference: () => order.push('reference'),
      delta: () => order.push('delta'),
      done: () => order.push('done'),
      error: () => order.push('error')
    })

    parser.push(raw)
    parser.flush()

    expect(order.filter((e) => e === 'reference')).toHaveLength(1)
    expect(order.filter((e) => e === 'delta')).toHaveLength(24)
    expect(order.filter((e) => e === 'done')).toHaveLength(1)
    expect(order[0]).toBe('reference')
    expect(order[order.length - 1]).toBe('done')
  })

  it('done 的字段与后端实际一致，且 messageId/sessionId 为 null 时不崩', () => {
    // 用对象包一层：避免 TS 把变量窄化成 null（赋值发生在回调里，控制流分析不到）
    const captured: { value: Record<string, unknown> | null } = { value: null }
    const parser = createSSEParser({
      done: (p) => {
        captured.value = { ...p }
      }
    })

    parser.push(raw)
    parser.flush()

    const done = captured.value
    expect(done).not.toBeNull()
    if (done == null) return

    expect(Object.keys(done).sort()).toEqual(
      ['content', 'finishStatus', 'firstTokenMs', 'latencyMs', 'messageId', 'sessionId'].sort()
    )
    // M1~M3 阶段后端明确传 null —— 前端必须容忍（这是本测试存在的直接原因）
    expect(done.messageId).toBeNull()
    expect(done.sessionId).toBeNull()
    expect(done.finishStatus).toBe(1)
    expect(String(done.content)).toContain('M1 阶段')
  })

  it('24 段 delta 拼起来等于 done 回传的全文（前端合帧校正的前提）', () => {
    const parts: string[] = []
    let fullText = ''
    const parser = createSSEParser({
      delta: (p) => parts.push(p.content),
      done: (p) => {
        fullText = p.content
      }
    })

    parser.push(raw)
    parser.flush()

    // 验证前端"增量可拼出全文"这一假设成立——
    // 合帧节流依赖 done 的 content 做校正，若增量与全文不一致，校正反而会丢字
    expect(parts.join('')).toBe(fullText)
  })
})
