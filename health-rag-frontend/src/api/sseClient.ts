/* ============================================================
 * SSE 客户端 —— 流式问答的唯一入口（前端设计.md §8.2）
 *
 * 与 api/request.ts 的关系：
 *   普通请求走 Axios；流式走这里的原生 fetch（EventSource 带不了
 *   Authorization 头）。两条通道【共用】utils/authError.ts 的同一份
 *   401/403 处理——这是 v1.1 修正的一致性漏洞，不允许再出现第二份。
 *
 * 错误分诊（必须区分，见前端设计.md §6.2 注 ⑥）：
 *   AbortError  用户点「停止」→ 静默返回，绝不弹报错
 *   ApiError    401/403 → handleAuthError（跳登录 / 轻提示）
 *   其他        网络异常 → 轻提示，不向上抛（流已终止，由 store 收尾）
 * ============================================================ */

import type { SSEHandlers, StreamChatRequest } from '@/api/types'
import { useUserStore } from '@/stores/user'
import { consumeSSE } from '@/utils/sse'
import type { ApiError } from '@/utils/apiError'
import { isAbortError } from '@/utils/apiError'
import { handleAuthError } from '@/utils/authError'
import { showMessage } from '@/utils/message'

const BASE: string = import.meta.env.VITE_API_BASE ?? '/api'

export async function streamChat(
  body: StreamChatRequest,
  handlers: SSEHandlers,
  signal?: AbortSignal
): Promise<void> {
  const token = useUserStore().token

  try {
    await consumeSSE(`${BASE}/chat/stream`, body, handlers, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      signal
    })
  } catch (e) {
    // ① 用户主动中止：不是故障，不能弹报错
    if (isAbortError(e)) return

    // ② 401/403：与 Axios 完全一致的唯一实现
    if (handleAuthError(e)) return

    // ③ 429 限流（流建立阶段，F-2）：与 request.ts 同文案，不终止于静默
    const err = e as ApiError
    if (err?.code === 429 || err?.httpStatus === 429) {
      showMessage('请求过于频繁，请稍后再试', 'warning')
      return
    }

    // ④ 其他异常：轻提示。不向上抛——流已终止，由 chat store 的
    //    finally 统一收尾（isStreaming = false），重复处理反而出两个提示
    showMessage(err?.message || '服务异常，请稍后重试', 'error')
  }
}
