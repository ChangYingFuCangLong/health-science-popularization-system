/* ============================================================
 * 用户个人知识库（契约：docs/V2/07-待实现接口-用户个人知识库.md #44–#52）
 *
 * 后端尚未实现。开发期由 mock 中间件按同一契约供数（见 /mock），
 * **本文件与组件就是最终代码**——后端就绪后只停用 mock，此处一行不改。
 *
 * 契约要点（与后端对齐时逐条核对）：
 *   - 越权一律 404（不暴露资源存在性），故本层不区分「不存在」与「不是你的」；
 *   - 新错误码 2006 配额超限 / 2007 文件不符，走统一 ApiError.code 即可；
 *   - status 语义复用 02 §6.3：0 待处理 / 1 解析中 / 2 完成 / 3 失败。
 * ============================================================ */

import type { PageResult } from '@/api/types'
import request, { del, get, put, type RequestOptions } from '@/api/request'
import {
  UPLOAD_ACCEPT,
  UPLOAD_ALLOWED_TYPES,
  UPLOAD_MAX_SIZE,
  validateUploadFile
} from '@/constants/upload'

/**
 * 检索范围的**唯一定义在 `@/api/types`**（契约 §5.4 的 StreamChatRequest 也用它），
 * 此处只作转出，避免两处各写一份字面量联合类型而悄悄漂移。
 */
export type { PageResult }

/** 文档处理状态（与 02 §6.3 同款状态机） */
export type PersonalDocStatus = 0 | 1 | 2 | 3

export interface PersonalKbDoc {
  id: number
  title: string
  /** pdf / docx / md / txt / html */
  fileType: string
  fileSize: number
  status: PersonalDocStatus
  /** status=3 时的失败原因；其余为 null */
  failReason: string | null
  chunkCount: number
  createTime: string
}

export interface KbQuota {
  maxDocuments: number
  maxTotalSize: number
  maxTotalChunks: number
  usedDocuments: number
  usedTotalSize: number
  usedTotalChunks: number
}

export interface PersonalKbStats {
  documentCount: number
  chunkCount: number
  totalSize: number
  readyCount: number
  processingCount: number
  failedCount: number
  /** 尚未向量化的分块数（仅诊断用；个人库未就绪**不会**触发全站 2003） */
  pendingChunks: number
  quota: KbQuota
}

export interface PersonalDocProgress {
  documentId: number
  status: PersonalDocStatus
  done: number
  /** 解析完成后才有值，之前为 null */
  total: number | null
  failReason: string | null
}

export interface PersonalChunk {
  id: number
  chunkIndex: number
  /** 章节路径（结构感知分块产出） */
  section: string | null
  content: string
  charCount: number
}

export interface PersonalKbSettings {
  /** 只读派生：有 ≥1 篇完成索引的文档时为 true */
  enabled: boolean
  /**
   * 参与问答总开关（02 v4.0 §6.5 #53 唯一开关）。
   * 07 号 v2 裁决：检索**恒为合并**（基础库+个人库），旧的三态 defaultScope 作废——
   * 范围不再由用户逐会话挑选，会话 kind 决定检索域。
   */
  includeInChat: boolean
}

export interface ListDocsParams {
  keyword?: string
  status?: PersonalDocStatus
  pageNum?: number
  pageSize?: number
}

/** 概览与配额（#44） */
export function getStats(options?: RequestOptions): Promise<PersonalKbStats> {
  return get<PersonalKbStats>('/user/kb/stats', undefined, options)
}

/** 文档列表（#45） */
export function listDocuments(
  params: ListDocsParams = {},
  options?: RequestOptions
): Promise<PageResult<PersonalKbDoc>> {
  return get<PageResult<PersonalKbDoc>>('/user/kb/documents', params, options)
}

/**
 * 上传（#46，multipart）。上传即受理，返回后需轮询 #47 获取进度。
 * `onProgress` 让用户看到「传到哪了」——个人库动辄几十 MB，无进度等于假死。
 */
export function uploadDocument(
  file: File,
  options?: { title?: string; onProgress?: (percent: number) => void }
): Promise<{ id: number; status: PersonalDocStatus }> {
  const form = new FormData()
  form.append('file', file)
  if (options?.title) form.append('title', options.title)

  return request
    .post<{ id: number; status: PersonalDocStatus }>('/user/kb/documents/upload', form, {
      // 解析+向量化由后端异步承担，这里只是传输，60s 足够
      timeout: 60_000,
      onUploadProgress: (e) => {
        if (!options?.onProgress) return
        const total = e.total ?? 0
        // 浏览器未给 total 时按不确定态处理（回调 -1，由 UI 显示为不定进度）
        options.onProgress(total > 0 ? Math.round((e.loaded / total) * 100) : -1)
      }
    })
    .then((res) => res.data)
}

/** 处理进度（#47）。建议轮询间隔 1.5s，status ∈ {2,3} 时停止 */
export function getProgress(id: number, options?: RequestOptions): Promise<PersonalDocProgress> {
  return get<PersonalDocProgress>(`/user/kb/documents/${id}/progress`, undefined, options)
}

/** 分块预览（#48）：让用户看见自己的文件被切成什么样 */
export function listChunks(
  id: number,
  params: { pageNum?: number; pageSize?: number } = {},
  options?: RequestOptions
): Promise<PageResult<PersonalChunk>> {
  return get<PageResult<PersonalChunk>>(`/user/kb/documents/${id}/chunks`, params, options)
}

/** 删除（#49）：级联清理向量、分块、文档与落盘文件 */
export function deleteDocument(id: number): Promise<null> {
  return del<null>(`/user/kb/documents/${id}`)
}

/** 单文档重新向量化（#50） */
export function rebuildDocument(id: number): Promise<null> {
  return request.post<null>(`/user/kb/documents/${id}/rebuild`).then((res) => res.data)
}

/** 偏好（#53 读取；enabled 为只读派生：有 ≥1 篇完成索引文档即 true） */
export function getSettings(options?: RequestOptions): Promise<PersonalKbSettings> {
  return get<PersonalKbSettings>('/user/kb/settings', undefined, options)
}

/** #53 偏好（全量提交；v4.0：includeInChat 唯一开关，存 user_kb_setting 表） */
export function updateSettings(payload: { includeInChat?: boolean }): Promise<null> {
  return put<null>('/user/kb/settings', payload)
}

/* ---------------- 展示辅助（纯函数，可在组件与单测中复用） ---------------- */

export const DOC_STATUS_LABEL: Record<PersonalDocStatus, string> = {
  0: '待处理',
  1: '处理中',
  2: '已索引入库',
  3: '处理失败'
}

/** 文件体积可读化：1.2 MB / 348 KB */
export function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 允许的文件类型——**唯一定义在 `@/constants/upload`**，与后端
 * `health.rag.upload-allowed-types`（txt/md/html/htm/pdf/docx）对齐。
 * 此处只作转出，避免个人库、管理端语料、报告识读各写一份字面量再次漂移。
 */
export const ALLOWED_TYPES = UPLOAD_ALLOWED_TYPES

export const ALLOWED_ACCEPT = UPLOAD_ACCEPT

/** 单文件大小上限（与服务端 upload-max-size-mb 一致，20MB） */
export const MAX_FILE_SIZE = UPLOAD_MAX_SIZE

/**
 * 上传前本地预校验（实现见 `@/constants/upload`）。
 * 服务端仍会再校验一次（客户端校验只是体验优化，不是安全边界）。
 */
export const validateFile = validateUploadFile
