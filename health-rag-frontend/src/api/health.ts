/* ============================================================
 * 健康档案接口层（02 v4.0 §六）
 *
 * 三段能力：
 *   6.1 健康指标 #40-#42（🟢）+ 指标目录化 #60-#62（🟡B-29）
 *       —— type 是目录 key（标准 key 或自定义 `c{defId}`），校验按目录定义（越界 3001）；
 *   6.3 报告识读 #63/#64 + 健康备忘 KV #65-#68（🟡B-30）
 *       —— extract 产出 10 分钟草稿，用户确认后 commit；数值入指标（source=report）、
 *          文本入 KV；越界项跳过计 skipped，不整批失败。
 * 设计依据：10 号（目录化）、11 号（报告双产物 / KV 分工）。
 * ============================================================ */

import { del, get, post, put } from '@/api/request'
import type { RequestOptions } from '@/api/request'

/* ---------------- 6.1 指标记录（#40-#42） ---------------- */

/** 记录类型 = 目录 key：标准 key（如 bp）或自定义 key（形如 c{defId}） */
export type MetricKey = string

export interface HealthMetric {
  id: number
  type: MetricKey
  /** 主数值：按目录单位；bp 时为收缩压 */
  value: number
  /** 仅双值指标（hasValue2，如血压）使用：舒张压；其余为 null */
  value2: number | null
  /** yyyy-MM-dd HH:mm:ss */
  recordedAt: string
  note: string | null
  /** 记录来源（🟡B-29）：manual 手动 / report 报告识读 */
  source?: 'manual' | 'report'
}

export interface CreateMetricPayload {
  type: MetricKey
  value: number
  value2?: number | null
  recordedAt?: string
  note?: string
}

/** #40 查询（type 缺省返回全部类型，含自定义；days 缺省 90） */
export function listHealthMetrics(
  params: { type?: MetricKey; days?: number } = {},
  options?: RequestOptions
): Promise<{ records: HealthMetric[] }> {
  return get<{ records: HealthMetric[] }>('/health/metrics', params, options)
}

/** #41 新增（校验按目录定义，越界 3001；未知 key → 400） */
export function createHealthMetric(
  payload: CreateMetricPayload,
  options?: RequestOptions
): Promise<{ id: number }> {
  return post<{ id: number }>('/health/metrics', payload, options)
}

/** #42 删除（物理删） */
export function deleteHealthMetric(id: number, options?: RequestOptions): Promise<null> {
  return del<null>(`/health/metrics/${id}`, options)
}

/* ---------------- 6.1 指标目录（#60-#62，🟡B-29） ---------------- */

export interface MetricDef {
  /** 标准 key 或自定义 `c{defId}` */
  key: string
  label: string
  unit: string | null
  valueMin: number | null
  valueMax: number | null
  /** 双值指标（血压 = true；自定义创建时可选） */
  hasValue2: boolean
  value2Label: string | null
  /** true = 用户自定义（仅本人可见；删除 = 逻辑删，记录只读保留） */
  custom: boolean
  /** 自定义定义 ID（custom 时有值，删除用） */
  defId?: number
}

export interface MetricCatalogCategory {
  name: string
  metrics: MetricDef[]
}

export interface MetricCatalog {
  categories: MetricCatalogCategory[]
}

/** #60 指标目录（标准 19 项 + 本人自定义，按分类分组） */
export function getMetricCatalog(options?: RequestOptions): Promise<MetricCatalog> {
  return get<MetricCatalog>('/health/metrics/catalog', undefined, options)
}

/** #61 创建自定义指标（≤10 个/人 → 2006；label 重复 → 400） */
export function createMetricDef(payload: {
  label: string
  unit?: string
  valueMin?: number | null
  valueMax?: number | null
  hasValue2?: boolean
  value2Label?: string | null
}): Promise<{ defId: number; key: string }> {
  return post<{ defId: number; key: string }>('/health/metric-defs', payload)
}

/** #62 删除自定义指标（标准指标 → 400；历史记录只读保留，标注「已删除指标」） */
export function deleteMetricDef(defId: number): Promise<null> {
  return del<null>(`/health/metric-defs/${defId}`)
}

/* ---------------- 6.3 报告识读（#63/#64，🟡B-30） ---------------- */

export interface ReportDraftItem {
  key: string
  value: string
  /** metric = 数值（进指标）；text = 信息型文本（进 KV） */
  type: 'metric' | 'text'
  /** 抽取时映射的标准目录 key；无法映射为 null（用户可手动选目录项或转 KV） */
  metricKey: string | null
  unit: string | null
  confidence: number
  value2?: string | null
}

export interface ReportExtractResult {
  draftId: string
  /** 同时并入个人资料库（P1）时返回；失败/未勾选为 null（两产物解耦） */
  kbDocumentId: number | null
  /** 识别的报告日期；识别不到 null（用户可改，作为 recordedAt） */
  reportDate: string | null
  items: ReportDraftItem[]
}

/**
 * #63 抽取（multipart）：LLM 抽取候选，草稿 TTL 10 分钟不入库。
 * alsoToKb=true 时同文件并入个人资料库流水线（失败不阻塞抽取，kbDocumentId=null）。
 * 失败 → 2001（解析失败）/ 2007（无有效文本）。限流 5 次/分钟/用户。
 */
export function extractReport(
  file: File,
  alsoToKb: boolean,
  options?: RequestOptions
): Promise<ReportExtractResult> {
  const form = new FormData()
  form.append('file', file)
  if (alsoToKb) form.append('alsoToKb', 'true')
  return post<ReportExtractResult>('/health/report/extract', form, options)
}

/** #64 确认入库：用户编辑后的候选提交；draftId 过期 → 400「草稿已过期」 */
export function commitReport(payload: {
  draftId: string
  reportDate: string | null
  items: ReportDraftItem[]
}): Promise<{ metricCount: number; kvCount: number; kbDocumentId: number | null }> {
  return post('/health/report/commit', payload)
}

/* ---------------- 6.3 健康备忘 KV（#65-#68，🟡B-30） ----------------
 * 分工裁决：指标 = 数值可趋势（目录化）；KV = 信息型文本（无趋势，注入个人解惑上下文）。
 * itemKey ≤50 字、itemValue ≤500 字、≤50 条/人（超 → 2006）；重复 key 允许。
 */

export interface HealthKvItem {
  id: number
  itemKey: string
  itemValue: string
  source?: 'manual' | 'report'
  createTime: string
  updateTime: string
}

/** #65 列表（create_time 倒序，非分页） */
export function listHealthKv(options?: RequestOptions): Promise<HealthKvItem[]> {
  return get<HealthKvItem[]>('/health/kv', undefined, options)
}

/** #66 新增 → { id } */
export function createHealthKv(
  itemKey: string,
  itemValue: string,
  options?: RequestOptions
): Promise<{ id: number }> {
  return post<{ id: number }>('/health/kv', { itemKey, itemValue }, options)
}

/** #67 修改（仅本人，越权 404） */
export function updateHealthKv(
  id: number,
  itemKey: string,
  itemValue: string,
  options?: RequestOptions
): Promise<null> {
  return put<null>(`/health/kv/${id}`, { itemKey, itemValue }, options)
}

/** #68 删除（物理删，仅本人） */
export function deleteHealthKv(id: number, options?: RequestOptions): Promise<null> {
  return del<null>(`/health/kv/${id}`, options)
}
