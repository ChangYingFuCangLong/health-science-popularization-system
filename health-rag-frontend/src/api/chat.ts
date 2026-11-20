import type { ChatMessage, ChatSession, PageResult } from '@/api/types'
import { del, get, post, put, type RequestOptions } from '@/api/request'

export function listSessions(): Promise<ChatSession[]> {
  return get<ChatSession[]>('/chat/sessions')
}

export function createSession(title?: string): Promise<ChatSession> {
  return post<ChatSession>('/chat/sessions', { title })
}

export function renameSession(id: number, title: string): Promise<null> {
  return put<null>(`/chat/sessions/${id}`, { title })
}

export function deleteSession(id: number): Promise<null> {
  return del<null>(`/chat/sessions/${id}`)
}

/**
 * 会话消息列表（分页，接口文档 4.5）。
 * 前端约定：默认加载最后一页（最近的消息）并定位到底部；
 * 用户上滚到顶时再加载更早一页。
 */
export function listMessages(
  sessionId: number,
  pageNum = 1,
  pageSize = 50
): Promise<PageResult<ChatMessage>> {
  return get<PageResult<ChatMessage>>(`/chat/sessions/${sessionId}/messages`, {
    pageNum,
    pageSize
  })
}

export type FeedbackType = 'useful' | 'useless' | 'wrong'

export function sendFeedback(
  messageId: number,
  type: FeedbackType,
  comment?: string
): Promise<null> {
  return post<null>(`/chat/messages/${messageId}/feedback`, { type, comment })
}

/**
 * 追问建议（02-接口文档 §4.9，V2 M10）。
 * 后端首次 LLM 生成后缓存，之后秒回；消息不存在/非助手/他人消息 → 404。
 * 前端 404 / 失败时回退到客户端派生（SuggestionChips）。
 */
export function fetchSuggestions(messageId: number): Promise<{ questions: string[] }> {
  return get<{ questions: string[] }>(
    `/chat/messages/${messageId}/suggestions`,
    undefined,
    { silent: true }
  )
}

/**
 * 语音输入：前端已于 2026-10-25 改用 Web Speech API（浏览器原生、零后端），
 * 见 composer/useSpeech.ts；UI 不再提供云端 ASR 入口。
 *
 * 注意：后端 `POST /api/chat/asr` 与错误码 2005 **仍保留可用**（未清码），
 * 只是前端不再调用——不要把"前端不用"理解成"端点不存在"。
 */

/* ---------------- 个人解惑会话（02 v4.0 §6.4，#58/#59） ----------------
 * kind='health' 会话的唯一管理入口（个人健康科普模块）。
 * 可创建多条（title 自定义）；健康信息全空 → 2008（引导先填写）。
 */

export function listHealthSessions(options?: RequestOptions): Promise<ChatSession[]> {
  return get<ChatSession[]>('/chat/sessions/health', undefined, options)
}

export function createHealthSession(
  title?: string,
  options?: RequestOptions
): Promise<ChatSession> {
  return post<ChatSession>('/chat/sessions/health', { title }, options)
}
