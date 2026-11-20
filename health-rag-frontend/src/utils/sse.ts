/* ============================================================
 * SSE 流解析器
 * 依据：docs/前端设计.md §6
 *
 * 为什么不用 EventSource：
 *   无法自定义请求头（带不了 Authorization）、只能 GET、无法可控中断。
 *
 * 本文件必须保持「纯」——不引用任何 store、不弹提示、不碰 router。
 * 依赖通过参数注入（fetchImpl / schedule），这样 10 条边界用例才能直接单测。
 * 认证错误（401/403）的处理放在 api/sseClient.ts —— 那里才允许碰 store 与 router。
 * ============================================================ */

import type { SSEHandlers } from '@/api/types'
import { toApiError } from '@/utils/apiError'

/** 单个事件块的分发（导出供单测直接覆盖） */
export function dispatchBlock(block: string, handlers: SSEHandlers): void {
  let eventName = 'message'
  const dataLines: string[] = []

  for (const rawLine of block.split(/\r?\n/)) {
    // ⑧ 以冒号开头的是注释行（常见于保活心跳），按 SSE 规范忽略
    if (rawLine.startsWith(':')) continue

    if (rawLine.startsWith('event:')) {
      eventName = rawLine.slice(6).trim()
    } else if (rawLine.startsWith('data:')) {
      // ⑨ 只去掉冒号后紧跟的【一个】空格，不要用 trim()——
      //    trim() 会吃掉回答内容本身的首尾空格，导致展示与原回答不一致
      dataLines.push(rawLine.slice(5).replace(/^ /, ''))
    }
  }

  // ⑩ 多行 data 按规范用 \n 连接，而不是直接拼接
  const raw = dataLines.join('\n')
  // 空 data 不分发
  if (!raw) return

  let payload: unknown
  try {
    payload = JSON.parse(raw)
  } catch {
    // ⑪ 单个坏帧不能拖垮整条流：跳过并留痕，后续帧继续处理
    console.warn('[sse] 无法解析的帧，已跳过：', raw)
    return
  }

  const handler = handlers[eventName as keyof SSEHandlers]
  if (typeof handler === 'function') {
    ;(handler as (p: unknown) => void)(payload)
  }
}

export interface SSEParser {
  /** 喂入一段【已解码】的文本（可能包含不完整的事件块） */
  push(text: string): void
  /** 流结束：把 buffer 里残留的最后一帧冲出去 */
  flush(): void
}

/**
 * 增量事件解析器。
 * 与网络层解耦——单测可直接喂字符串，无需构造 ReadableStream。
 */
export function createSSEParser(handlers: SSEHandlers): SSEParser {
  let buffer = ''

  return {
    push(text: string): void {
      buffer += text

      // ③ 按空行切分事件块。SSE 规范同时允许 \n\n 与 \r\n\r\n，
      //    经网关 / Nginx 转发后 CRLF 很常见；只处理 \n\n 会整条流解析不出来
      const blocks = buffer.split(/\r?\n\r?\n/)
      // ④ 最后一段可能接收不完整，留在 buffer 等下一轮
      buffer = blocks.pop() ?? ''

      for (const block of blocks) {
        dispatchBlock(block, handlers)
      }
    },

    flush(): void {
      // ⑤ 流结束时若末尾没有空行，最后一帧仍在 buffer 里，必须再冲一次
      const rest = buffer
      buffer = ''
      if (rest.trim()) dispatchBlock(rest, handlers)
    }
  }
}

export interface ConsumeSSEOptions {
  /** 额外请求头（如 Authorization），由调用方注入 */
  headers?: Record<string, string>
  signal?: AbortSignal
  /** 便于单测注入；默认用全局 fetch */
  fetchImpl?: typeof fetch
}

/**
 * 发起一次 SSE 请求并消费到底。
 *
 * 抛出的错误分三类，调用方需分别处理：
 *   - ApiError      业务/HTTP 错误（含 401/403，由调用方决定是否跳登录）
 *   - DOMException   AbortError —— 用户主动停止，应静默忽略
 *   - 其他          网络异常
 */
export async function consumeSSE(
  url: string,
  body: unknown,
  handlers: SSEHandlers,
  options: ConsumeSSEOptions = {}
): Promise<void> {
  const { headers = {}, signal, fetchImpl = fetch } = options

  const res = await fetchImpl(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...headers
    },
    body: JSON.stringify(body),
    signal
  })

  // ① fetch 在 4xx/5xx 时【不会 reject】。不判断 res.ok 的话，
  //    错误会被静默吞掉，界面永远停在"生成中"——最难排查的一种现象
  if (!res.ok) {
    throw await toApiError(res)
  }
  if (!res.body) {
    throw new Error('响应无 body，无法读取流')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')

  /**
   * 协议上 `done` / `error` 就是最后一帧。记录它是否已到达，
   * 用于区分「流已完整但连接关闭」与「流被真的截断」。
   */
  let terminated = false
  const guardedHandlers: SSEHandlers = {
    reference: handlers.reference,
    // stage / answer_* 是过程信号（02 v4.0 §4.3），不是终止帧：透传即可。
    // ⚠️ 这里的白名单必须随 SSEHandlers 接口同步扩展——曾因漏列 answer_start
    // 导致对照解答的分段事件被静默丢弃、双答案渲染成单卡（页面无任何报错）。
    stage: handlers.stage,
    answer_start: handlers.answer_start,
    answer_end: handlers.answer_end,
    delta: handlers.delta,
    done: (payload) => {
      terminated = true
      handlers.done?.(payload)
    },
    error: (payload) => {
      terminated = true
      handlers.error?.(payload)
    }
  }

  const parser = createSSEParser(guardedHandlers)

  /**
   * ⑯【关键】不依赖"连接关闭"判断流结束。
   *
   * 协议上 `done` / `error` 就是最后一帧，收到即代表本次回答已完整送达。
   * 实测（2026-09-18）后端发完 `done` 后**连接是异常关闭的**：
   *   - curl 退出码 18（Transferred a partial file）
   *   - Node/undici 抛 `TypeError: terminated`，而非返回 { done: true }
   *   - 浏览器侧 `reader.read()` 可能长时间不返回 → 界面永久停在"生成中"
   *
   * 因此收到终止帧后主动收尾：不再等 EOF，直接 cancel 释放连接。
   * 这样无论服务端关闭得是否规范，前端行为都一致且不会卡住。
   */
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      // ② 必须 { stream: true }，否则中文多字节字符会在 chunk 边界被截断成乱码
      parser.push(decoder.decode(value, { stream: true }))
      // 收到终止帧 → 立即结束，不等连接关闭
      if (terminated) break
    }
    // 冲掉解码器内部残留的半个多字节字符，再冲最后一帧
    parser.push(decoder.decode())
    parser.flush()
  } catch (e) {
    // 已收到终止帧：后续的连接断开不算失败（服务器关闭方式不规范）。
    // 未收到终止帧：属于真实的流截断，必须抛出，不能静默当成成功。
    if (!terminated) throw e
  } finally {
    if (terminated) {
      // 主动释放连接，避免半开连接挂住（服务端可能没有正常关闭）。
      // 用 try 包住：cancel 在部分运行时/替身实现中可能不存在，
      // 它失败不应影响"流已正常结束"这一结论。
      try {
        await reader.cancel()
      } catch {
        /* 忽略 */
      }
    }
    try {
      reader.releaseLock()
    } catch {
      /* 忽略 */
    }
  }
}
