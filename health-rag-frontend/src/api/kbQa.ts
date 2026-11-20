/* ============================================================
 * 文档解惑（02 v4.0 §五，#69–#75 · 设计依据 12 号）
 *
 * kind='file' 会话 + 会话文件集（多对多）：检索范围 = 该会话的文件集，
 * 流式问答复用 §4.3 协议零新参数（sessionId 传 file 会话即可）。
 *
 * 配额（超限 → 2006）：每会话 ≤10 份文件、单文件 ≤20MB 且 ≤500 块、
 * 解惑会话 ≤20 个/人；文件不符 → 2007。
 * 行为裁决：会话内存在处理中（status 0/1）文件时 #74/#75 → 400；
 * 空文件集提问 → 400「请先上传文件」；通用 DELETE /chat/sessions/{id}
 * 对 file 会话 → 400（必须走 #75 级联，防孤儿文件）。
 * ============================================================ */

import type { SessionKind } from '@/api/types'
import { del, get, post } from '@/api/request'
import type { RequestOptions } from '@/api/request'

export interface QaSession {
  id: number
  title: string
  kind: Extract<SessionKind, 'file'>
  /** 会话文件数（#70 汇总） */
  documentCount: number
  /** 处理中文件数（#70 汇总，>0 时禁止删除会话） */
  processingCount: number
  messageCount: number
  createTime: string
  updateTime: string
}

export interface QaDocument {
  id: number
  title: string
  /** txt / md / html / htm / pdf / docx（与后端 upload-allowed-types 一致） */
  fileType: string
  fileSize: number
  status: 0 | 1 | 2 | 3
  failReason: string | null
  chunkCount: number
  createTime: string
}

export interface QaDocumentProgress {
  documentId: number
  status: 0 | 1 | 2 | 3
  done: number
  total: number | null
  failReason: string | null
}

/** #69 创建解惑会话（title 缺省「文档解惑」） */
export function createQaSession(title?: string): Promise<QaSession> {
  return post<QaSession>('/knowledge/qa/sessions', { title })
}

/** #70 我的解惑列表（updateTime 倒序，含文件数与处理状态汇总） */
export function listQaSessions(options?: RequestOptions): Promise<QaSession[]> {
  return get<QaSession[]>('/knowledge/qa/sessions', undefined, options)
}

/** #71 上传文件到会话（multipart，可多次调用 = 动态追加） */
export function uploadQaDocument(
  sessionId: number,
  file: File,
  options?: { onProgress?: (percent: number) => void } & RequestOptions
): Promise<{ documentId: number; status: 1 }> {
  const form = new FormData()
  form.append('file', file)
  return post<{ documentId: number; status: 1 }>(
    `/knowledge/qa/sessions/${sessionId}/documents`,
    form,
    options
  )
}

/** #72 会话文件列表 */
export function listQaDocuments(
  sessionId: number,
  options?: RequestOptions
): Promise<QaDocument[]> {
  return get<QaDocument[]>(`/knowledge/qa/sessions/${sessionId}/documents`, undefined, options)
}

/** #73 单文件进度（1.5s 轮询，status ∈ {2,3} 停止）；通用前缀 /api/knowledge/qa/** */
export function getQaDocumentProgress(
  documentId: number,
  options?: RequestOptions
): Promise<QaDocumentProgress> {
  return get<QaDocumentProgress>(
    `/knowledge/qa/documents/${documentId}/progress`,
    undefined,
    options
  )
}

/** #74 从会话移除文件（级联分块+向量+落盘；会话保留可续传；处理中 → 400） */
export function deleteQaDocument(documentId: number): Promise<null> {
  return del<null>(`/knowledge/qa/documents/${documentId}`)
}

/** #75 删除解惑会话（级联全部文件+消息；处理中 → 400） */
export function deleteQaSession(sessionId: number): Promise<null> {
  return del<null>(`/knowledge/qa/sessions/${sessionId}`)
}
