// @vitest-environment node
// 纯函数单测，不需要 DOM。用例表见 docs/前端设计.md §6.5
import { describe, expect, it, vi } from 'vitest'
import { consumeSSE, createSSEParser, dispatchBlock } from '@/utils/sse'
import type { SSEHandlers } from '@/api/types'
import { ApiError, isAbortError } from '@/utils/apiError'

const enc = new TextEncoder()

function concat(...arrays: Uint8Array[]): Uint8Array {
  const total = arrays.reduce((n, a) => n + a.length, 0)
  const out = new Uint8Array(total)
  let offset = 0
  for (const a of arrays) {
    out.set(a, offset)
    offset += a.length
  }
  return out
}

/** 构造最小的 Response 替身 —— 只需 ok / body.getReader() */
function fakeResponse(chunks: Uint8Array[]): Response {
  let i = 0
  return {
    ok: true,
    body: {
      getReader() {
        return {
          async read() {
            if (i < chunks.length) return { done: false, value: chunks[i++] }
            return { done: true, value: undefined }
          },
          async cancel() {
            /* noop */
          },
          releaseLock() {
            /* noop */
          }
        }
      }
    }
  } as unknown as Response
}

function okFetch(chunks: Uint8Array[]): typeof fetch {
  return (async () => fakeResponse(chunks)) as unknown as typeof fetch

}

/* ---------------- 解析器层 ---------------- */

describe('createSSEParser', () => {
  it('① 基础事件：event + data 正确分发', () => {
    const deltas: string[] = []
    const parser = createSSEParser({ delta: (p) => deltas.push(p.content) })
    parser.push('event: delta\ndata: {"content":"高"}\n\n')
    expect(deltas).toEqual(['高'])
  })

  it('② CRLF 分隔：经网关转发后的 \\r\\n\\r\\n 同样能解析', () => {
    const deltas: string[] = []
    const parser = createSSEParser({ delta: (p) => deltas.push(p.content) })
    parser.push('event: delta\r\ndata: {"content":"血压"}\r\n\r\n')
    expect(deltas).toEqual(['血压'])
  })

  it('③ 事件块被切成多次 push：未完整时不分发，补齐后分发', () => {
    const deltas: string[] = []
    const parser = createSSEParser({ delta: (p) => deltas.push(p.content) })

    parser.push('event: delta\ndata: {"con')
    expect(deltas).toEqual([]) // 未完整，留在 buffer

    parser.push('tent":"高"}\n\n')
    expect(deltas).toEqual(['高'])
  })

  it('④ 多行 data 用 \\n 连接（换行落在 JSON 词法之外，仍是合法 JSON）', () => {
    const received: Record<string, unknown>[] = []
    const parser = createSSEParser({ delta: (p) => received.push({ ...p }) })

    // 两个 data 行 → 用 \n 连接 → 换行位于 JSON 的 token 之间，因此可被解析。
    // 反例（不成立）：若换行落在 JSON 字符串字面量内部，拼接结果就是非法 JSON。
    // 因此后端事件体应【单行输出 JSON】——这也是本系统的实际做法。
    parser.push('event: delta\ndata: {"content":"第一行"\ndata: ,"extra":"第二行"}\n\n')

    expect(received).toEqual([{ content: '第一行', extra: '第二行' }])
  })

  it('⑤ 内容首尾空格被保留（不能用 trim）', () => {
    const deltas: string[] = []
    const parser = createSSEParser({ delta: (p) => deltas.push(p.content) })
    parser.push('event: delta\ndata: {"content":"  "}\n\n')
    expect(deltas).toEqual(['  '])
  })

  it('⑥ 坏帧被跳过，后续帧继续处理', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const deltas: string[] = []
    const parser = createSSEParser({ delta: (p) => deltas.push(p.content) })

    parser.push('event: delta\ndata: {oops\n\nevent: delta\ndata: {"content":"正常"}\n\n')

    expect(warn).toHaveBeenCalledTimes(1)
    expect(deltas).toEqual(['正常'])
    warn.mockRestore()
  })

  it('⑦ 注释行（心跳）被忽略', () => {
    const calls: string[] = []
    const parser = createSSEParser({ delta: (p) => calls.push(p.content) })
    parser.push(': keep-alive\n\nevent: delta\ndata: {"content":"ok"}\n\n')
    expect(calls).toEqual(['ok'])
  })

  it('⑧ 空 data 不分发', () => {
    const handler = vi.fn()
    dispatchBlock('event: delta\ndata:', { delta: handler })
    expect(handler).not.toHaveBeenCalled()
  })

  it('⑨ flush() 冲出末尾没有空行的最后一帧', () => {
    const deltas: string[] = []
    const parser = createSSEParser({ delta: (p) => deltas.push(p.content) })
    parser.push('event: delta\ndata: {"content":"最后一帧"}')
    expect(deltas).toEqual([])
    parser.flush()
    expect(deltas).toEqual(['最后一帧'])
  })

  it('⑩ 未声明的事件名静默忽略，不报错', () => {
    expect(() => dispatchBlock('event: unknown\ndata: {"a":1}', {})).not.toThrow()
  })

  it('⑱ stage 事件透传（V2 M10 过程信号）：帧格式为冒号后无空格 + LF', () => {
    const stages: Array<Record<string, unknown>> = []
    const parser = createSSEParser({ stage: (p) => stages.push({ ...p }) })
    parser.push(
      'event:stage\ndata:{"step":"retrieve_start"}\n\n' +
        'event:stage\ndata:{"step":"retrieve_done","rewrittenQuery":"高血压 预防"}\n\n'
    )
    expect(stages).toEqual([
      { step: 'retrieve_start' },
      { step: 'retrieve_done', rewrittenQuery: '高血压 预防' }
    ])
  })
})

/* ---------------- 网络层 ---------------- */
describe('consumeSSE', () => {
  it('⑪ 中文多字节字符被切成两个 chunk 仍能正确解码（{ stream: true }）', async () => {
    const head = enc.encode('event: delta\ndata: {"content":"')
    const gao = enc.encode('高') // UTF-8: e9 ab 98
    const tail = enc.encode('"}\n\n')

    const part1 = concat(head, gao.slice(0, 1))
    const part2 = concat(gao.slice(1), tail)

    const deltas: string[] = []
    await consumeSSE(
      '/api/chat/stream',
      {},
      { delta: (p) => deltas.push(p.content) },
      { fetchImpl: okFetch([part1, part2]) }
    )

    expect(deltas).toEqual(['高'])
  })

  it('⑫ HTTP 401 抛出 ApiError（fetch 不会 reject，必须显式判断 res.ok）', async () => {
    const fake = {
      ok: false,
      status: 401,
      text: async () => JSON.stringify({ code: 401, message: '登录状态已失效', data: null })
    } as unknown as Response

    const promise = consumeSSE(
      '/api/chat/stream',
      {},
      {},
      { fetchImpl: (async () => fake) as unknown as typeof fetch }
    )

    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await expect(promise).rejects.toMatchObject({ code: 401, message: '登录状态已失效' })
  })

  it('⑬ AbortError 向上传播，且可被 isAbortError 识别（点「停止」不该弹报错）', async () => {
    const abortErr = new Error('The user aborted a request.')
    abortErr.name = 'AbortError'

    const promise = consumeSSE(
      '/api/chat/stream',
      {},
      {},
      {
        fetchImpl: (async () => {
          throw abortErr
        }) as unknown as typeof fetch
      }
    )

    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    expect(isAbortError(abortErr)).toBe(true)
  })

  it('⑭ 携带 Authorization 头（EventSource 做不到，这是必须用 fetch 的理由）', async () => {
    let captured: HeadersInit | undefined

    const spy = (async (_url: string, init: RequestInit) => {
      captured = init.headers as HeadersInit
      return fakeResponse([])
    }) as unknown as typeof fetch

    await consumeSSE('/api/chat/stream', { question: 'x' }, {}, {
      fetchImpl: spy,
      headers: { Authorization: 'Bearer test-token' }
    })

    expect(captured).toMatchObject({
      Authorization: 'Bearer test-token',
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    })
  })

  it('⑲ answer_start/answer_end 透传（对照解答 B-27）——guardedHandlers 白名单回归', async () => {
    // 事故（2026-10-25）：consumeSSE 的 guardedHandlers 白名单漏列 answer_start/answer_end，
    // 分段事件被静默丢弃、双答案渲染成单卡且页面无任何报错。本用例锁定透传行为。
    const seen: string[] = []
    const frames = enc.encode(
      'event:answer_start\ndata:{"answerId":1,"source":"global","label":"系统版"}\n\n' +
        'event:answer_end\ndata:{"answerId":1,"messageId":901}\n\n'
    )
    await consumeSSE(
      '/api/chat/stream',
      {},
      {
        answer_start: (p) => seen.push(`answer_start:${p.source}`),
        answer_end: (p) => seen.push(`answer_end:${p.messageId}`)
      },
      { fetchImpl: okFetch([frames]) }
    )
    expect(seen).toEqual(['answer_start:global', 'answer_end:901'])
  })

  it('⑯a 收到 done 后不再等待连接关闭（服务端可能一直不关，会卡住界面）', async () => {
    let reads = 0
    let cancelled = false
    const reader = {
      async read() {
        reads++
        if (reads === 1) {
          return {
            done: false,
            value: enc.encode(
              'event: done\ndata: {"messageId":null,"sessionId":null,"latencyMs":1,"firstTokenMs":1,"finishStatus":1,"content":"完"}\n\n'
            )
          }
        }
        // 第二次 read 永不返回——模拟服务端迟迟不关闭连接
        return await new Promise<never>(() => {})
      },
      async cancel() {
        cancelled = true
      },
      releaseLock() {
        /* noop */
      }
    }
    const fake = {
      ok: true,
      body: { getReader: () => reader }
    } as unknown as Response

    const seen: string[] = []
    await consumeSSE('/api/chat/stream', {}, { done: () => seen.push('done') }, {
      fetchImpl: (async () => fake) as unknown as typeof fetch
    })

    expect(seen).toEqual(['done'])
    expect(reads).toBe(1) // 收到 done 后没有再去 read
    expect(cancelled).toBe(true) // 主动释放了连接
  })

  it('⑯ 收到 done 之后的连接关闭视为正常结束（Node/undici 会抛 terminated）', async () => {
    const chunks: Uint8Array[] = [
      enc.encode('event: delta\ndata: {"content":"内容"}\n\n'),
      enc.encode('event: done\ndata: {"messageId":null,"sessionId":null,"latencyMs":1,"firstTokenMs":1,"finishStatus":1,"content":"内容"}\n\n')
    ]

    // 模拟 undici：最后一帧送达后，连接被服务端关闭 → read() 抛错而非返回 done
    const reader = {
      async read() {
        if (chunks.length > 0) return { done: false, value: chunks.shift() }
        const err = new Error('terminated')
        err.name = 'TypeError'
        throw err
      },
      releaseLock() {
        /* noop */
      }
    }
    const fake = {
      ok: true,
      body: { getReader: () => reader }
    } as unknown as Response

    const seen: string[] = []
    await expect(
      consumeSSE('/api/chat/stream', {}, {
        delta: (p) => seen.push(p.content),
        done: () => seen.push('done')
      }, { fetchImpl: (async () => fake) as unknown as typeof fetch })
    ).resolves.toBeUndefined()

    expect(seen).toEqual(['内容', 'done'])
  })

  it('⑰ 未收到终止事件就被关闭 → 必须抛出（不能被上一条逻辑吞掉）', async () => {
    const reader = {
      async read() {
        const err = new Error('terminated')
        err.name = 'TypeError'
        throw err
      },
      releaseLock() {
        /* noop */
      }
    }
    const fake = {
      ok: true,
      body: { getReader: () => reader }
    } as unknown as Response

    await expect(
      consumeSSE('/api/chat/stream', {}, {}, {
        fetchImpl: (async () => fake) as unknown as typeof fetch
      })
    ).rejects.toThrow('terminated')
  })

  it('⑮ 完整链路：reference → delta ×n → done 按顺序到达', async () => {
    const stream =
      'event: reference\ndata: {"chunks":[{"chunkId":1,"docId":9,"doc":"高血压防治指南","section":"饮食","digest":"…","score":0.87}]}\n\n' +
      'event: delta\ndata: {"content":"高"}\n\n' +
      'event: delta\ndata: {"content":"血压"}\n\n' +
      'event: done\ndata: {"messageId":10086,"sessionId":12,"latencyMs":3200,"firstTokenMs":820,"finishStatus":1,"content":"高血压是…"}\n\n'

    const seen: string[] = []
    const handlers: SSEHandlers = {
      reference: () => seen.push('reference'),
      delta: () => seen.push('delta'),
      done: () => seen.push('done')
    }

    await consumeSSE('/api/chat/stream', {}, handlers, {
      fetchImpl: okFetch([enc.encode(stream)])
    })

    expect(seen).toEqual(['reference', 'delta', 'delta', 'done'])
  })
})
