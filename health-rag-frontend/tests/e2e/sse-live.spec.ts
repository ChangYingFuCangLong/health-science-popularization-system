// @vitest-environment node
/* ============================================================
 * 端到端联调（默认跳过）
 *
 * 默认 skip —— 它需要后端与数据库在运行。
 * 需要跑时：先确保后端 8080 可用，再执行
 *     E2E_LIVE=1 npm run test:unit
 *
 * 覆盖：真实网络 + 真实鉴权 + 真实流式，走完整的 consumeSSE 路径
 * （而不是只测解析器）。这是唯一能证明"前后端真的对得上"的测试。
 * ============================================================ */

import { describe, expect, it } from 'vitest'
import { consumeSSE } from '@/utils/sse'
import type { SSEHandlers } from '@/api/types'

const BASE = process.env.E2E_BASE ?? 'http://localhost:8080'
const USER = process.env.E2E_USER ?? 'qian_e2e'
const PASS = process.env.E2E_PASSWORD ?? 'test123456'
const LIVE = process.env.E2E_LIVE === '1'

async function login(): Promise<string> {
  // 用真实注册接口确保账号存在（已存在时返回 1001，忽略即可）
  await fetch(`${BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS })
  }).catch(() => undefined)

  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS })
  })
  const body = (await res.json()) as { code: number; data?: { token?: string } }
  if (!body.data?.token) throw new Error(`登录失败：${JSON.stringify(body)}`)
  return body.data.token
}

describe.skipIf(!LIVE)('SSE 端到端（真实后端）', () => {
  it('带 token 能走完 reference → delta×n → done', async () => {
    const token = await login()

    const order: string[] = []
    let fullText = ''
    const handlers: SSEHandlers = {
      reference: () => order.push('reference'),
      delta: () => order.push('delta'),
      done: (p) => {
        order.push('done')
        fullText = p.content
      }
    }

    await consumeSSE(
      `${BASE}/api/chat/stream`,
      { question: '感冒了怎么办', sessionId: null },
      handlers,
      { headers: { Authorization: `Bearer ${token}` } }
    )

    expect(order[0]).toBe('reference')
    expect(order[order.length - 1]).toBe('done')
    expect(order.filter((e) => e === 'delta').length).toBeGreaterThan(0)
    expect(fullText.length).toBeGreaterThan(0)
  }, 30_000)

  it('缺少 Authorization 时应抛出 ApiError（401），而不是静默卡住', async () => {
    await expect(
      consumeSSE(`${BASE}/api/chat/stream`, { question: '感冒了怎么办' }, {})
    ).rejects.toMatchObject({ code: 401 })
  }, 20_000)
})
