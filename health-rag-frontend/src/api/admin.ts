import type { PageResult } from '@/api/types'
import { del, get, post, put } from '@/api/request'
import type { RequestOptions } from '@/api/request'

/* ============================================================
 * 管理端接口层
 *
 * ⚠️【重要】字段名以【后端实际实现】为准，不以接口文档为准。
 *    2026-09-18 核对后端 M8 源码（AdminDTO / AdminController / EvalController）后
 *    与 docs/接口文档.md 发现多处不一致，已按后端实际修正
 *    （要点：概览/分块/日志/反馈/评测的字段命名与分块接口的数组返回形态）。
 *    **若后端后续改回文档命名，这里必须同步改。**
 *
 * 状态：后端已实现全部管理端写操作（含上传，02 §6.2/§6.3）。
 *    上传为异步流水线：POST /admin/documents/upload 受理后，
 *    前端按 §6.3 以 2s 轮询进度，status=2 停止并刷新、status=3 展示 failReason。
 * ============================================================ */

/* ---------------- 统计概览 ---------------- */

export interface OverviewStats {
  totalUsers: number
  totalSessions: number
  totalMessages: number
  todayMessages: number
  rejectRate: number
  /** ⚠️ 后端给的是【整体响应耗时均值】，没有首字延迟均值 */
  avgLatencyMs: number
  /** 首字延迟均值（F-1，后端 M9 补 AVG(first_token_ms)）；V1 后端无此字段 → null */
  avgFirstTokenMs: number | null
  docCount: number
  chunkCount: number
  trend: Array<{ date: string; count: number }>
  hotQuestions: Array<{ question: string; count: number }>
}

export interface VectorMeta {
  embeddingModel: string
  dimension: number
  chunkTotal: number
  status: number
  builtAt: string
}

/* ---------------- 语料 ---------------- */

/** 后端直接返回 KbDocument 实体 */
export interface AdminDocument {
  id: number
  title: string
  source: string
  category: string
  fileType: string
  filePath: string
  fileSize: number
  status: number
  failReason: string | null
  chunkCount: number
  uploadBy: number
  createTime: string
  updateTime: string
}

/** ⚠️ 分块接口返回的是【数组】，不是分页对象；section 为 V2 新增（历史数据可能为空串） */
export interface AdminChunk {
  id: number
  chunkIndex: number
  content: string
  charCount: number
  section?: string
  metaJson: Record<string, unknown> | null
}

/* ---------------- 日志 / 反馈 / 用户 ---------------- */

export interface AdminChatLog {
  messageId: number
  sessionId: number
  userId: number
  username: string
  question: string
  answer: string
  /** 改写后检索词（F-1，后端 B-10/M9.5 后产生；历史数据为 null） */
  queryText: string | null
  retrieveMode: string | null
  latencyMs: number | null
  isRejected: number | null
  finishStatus: number | null
  createTime: string
}

export interface AdminFeedback {
  id: number
  messageId: number
  userId: number
  username: string
  type: string
  comment: string | null
  status: number
  /** 被反馈回答的摘要（后端不返回整段回答） */
  answerDigest: string | null
  createTime: string
}

export interface AdminUser {
  id: number
  username: string
  nickname: string
  email: string | null
  role: string
  status: number
  lastLoginAt: string | null
  createTime: string
}

/* ---------------- 评测 ---------------- */

export interface EvalDatasetItem {
  id: number
  question: string
  groundTruth: string | null
  refChunkIds: number[] | null
  category: string | null
  difficulty: number | null
  createTime: string
}

export interface EvalResultItem {
  id: number
  batchNo: string
  datasetId: number
  question: string
  strategy: string
  /** 命中位次；未命中为 null */
  hitRank: number | null
  isHit: number
  faithfulness: number | null
  citationCorrect: number | null
  latencyMs: number | null
}

export interface EvalSummaryItem {
  strategy: string
  hitRate5: number
  mrr: number
  avgFaithfulness: number
  avgLatencyMs: number
  sampleCount: number
}

export interface EvalSummary {
  batchNo: string
  items: EvalSummaryItem[]
}

/* ---------------- 通用参数 ---------------- */

export interface PageQuery {
  pageNum: number
  pageSize: number
}

/* ---------------- 接口 ---------------- */

export function getOverview(options?: RequestOptions): Promise<OverviewStats> {
  return get<OverviewStats>('/admin/stats/overview', undefined, options)
}

export function getVectorMeta(options?: RequestOptions): Promise<VectorMeta> {
  return get<VectorMeta>('/admin/vectors/meta', undefined, options)
}

export function listDocuments(
  params: PageQuery & { keyword?: string; category?: string; status?: number },
  options?: RequestOptions
): Promise<PageResult<AdminDocument>> {
  return get<PageResult<AdminDocument>>('/admin/documents', params, options)
}

/** 上传受理结果（02 §6.2）：处理为异步流水线，进度走 getDocumentProgress */
export interface UploadDocumentResult {
  documentId: number
  status: number
  message: string
}

/**
 * 上传文档（02 §6.2）：multipart，file 必填（txt/md/pdf/docx/html，≤20MB），
 * title/category/source 可选（缺省 title 取文件名去扩展名）。
 * 客户端预检（扩展名白名单 + 20MB）由 DocumentView 的 before-upload 承担。
 */
export function uploadDocument(
  file: File,
  meta: { title?: string; category?: string; source?: string } = {}
): Promise<UploadDocumentResult> {
  const form = new FormData()
  form.append('file', file)
  if (meta.title) form.append('title', meta.title)
  if (meta.category) form.append('category', meta.category)
  if (meta.source) form.append('source', meta.source)
  return post('/admin/documents/upload', form)
}

/** 处理进度（02 §6.3）：status 0 待处理 / 1 处理中 / 2 已完成 / 3 失败 */
export interface DocumentProgress {
  documentId: number
  status: number
  chunksDone: number
  chunksTotal: number
  failReason: string | null
}

export function getDocumentProgress(
  id: number,
  options?: RequestOptions
): Promise<DocumentProgress> {
  return get<DocumentProgress>(`/admin/documents/${id}/progress`, undefined, options)
}

/** 同步删除：连同该文档的分块与向量一并逻辑删除 */
export function deleteDocument(id: number): Promise<unknown> {
  return del(`/admin/documents/${id}`)
}

/** 异步重建单文档索引：立即返回；重建期间重复提交返回 400，进度轮询 vectors/meta */
export function rebuildDocument(id: number): Promise<unknown> {
  return post(`/admin/documents/${id}/rebuild`)
}

/** 后端返回数组，不是分页 */
export function listChunks(
  id: number,
  options?: RequestOptions
): Promise<AdminChunk[]> {
  return get<AdminChunk[]>(`/admin/documents/${id}/chunks`, undefined, options)
}

/** 异步全量重建：立即返回；进行中重复提交返回 400，进度看 vectors/meta 的 status */
export function rebuildVectors(): Promise<unknown> {
  return post('/admin/vectors/rebuild')
}

/** 后端支持 userId / isRejected / startDate / endDate，【不支持 keyword】 */
export function listChatLogs(
  params: PageQuery & { userId?: number; isRejected?: number },
  options?: RequestOptions
): Promise<PageResult<AdminChatLog>> {
  return get<PageResult<AdminChatLog>>('/admin/chat-logs', params, options)
}

export function listFeedbacks(
  params: PageQuery & { status?: number },
  options?: RequestOptions
): Promise<PageResult<AdminFeedback>> {
  return get<PageResult<AdminFeedback>>('/admin/feedbacks', params, options)
}

/** 标记反馈已处理，body { status: 1 } */
export function resolveFeedback(id: number): Promise<unknown> {
  return put(`/admin/feedbacks/${id}`, { status: 1 })
}

/** keyword 支持用户名/昵称模糊搜索（F1，后端已实现） */
export function listUsers(
  params: PageQuery & { keyword?: string },
  options?: RequestOptions
): Promise<PageResult<AdminUser>> {
  return get<PageResult<AdminUser>>('/admin/users', params, options)
}

/** 启用/禁用用户；后端禁止禁用当前登录的管理员自己（返回 400） */
export function setUserStatus(id: number, status: number): Promise<unknown> {
  return put(`/admin/users/${id}/status`, { status })
}

/**
 * #44 删除用户（🟡B-25）：不存在 404；删自己/删 ADMIN → 400。
 * 副作用：deleted=true + 踢下线 + 健康数据/个人资料库/文档解惑物理删；
 * 会话消息保留（username 置空「已注销用户」）。
 */
export function deleteUser(id: number): Promise<null> {
  return del<null>(`/admin/users/${id}`)
}

export function listEvalDataset(
  params: PageQuery & { category?: string },
  options?: RequestOptions
): Promise<PageResult<EvalDatasetItem>> {
  return get<PageResult<EvalDatasetItem>>('/admin/eval/dataset', params, options)
}

export function createEvalDataset(item: {
  question: string
  groundTruth: string
  refChunkIds?: number[]
  category?: string
  difficulty?: number
}): Promise<{ id: number }> {
  return post('/admin/eval/dataset', item)
}

/** 后端入参是 strategies[] + datasetIds[] */
export function runEval(body: {
  strategies: string[]
  datasetIds?: number[]
}): Promise<{ batchNo: string }> {
  return post('/admin/eval/run', body)
}

export function listEvalResults(
  params: PageQuery & { batchNo?: string; strategy?: string },
  options?: RequestOptions
): Promise<PageResult<EvalResultItem>> {
  return get<PageResult<EvalResultItem>>('/admin/eval/results', params, options)
}

/** 返回 { batchNo, items[] }，不是数组 */
export function getEvalSummary(
  params: { batchNo?: string },
  options?: RequestOptions
): Promise<EvalSummary> {
  return get<EvalSummary>('/admin/eval/summary', params, options)
}
