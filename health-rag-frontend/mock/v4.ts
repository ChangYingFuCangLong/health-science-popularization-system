/* ============================================================
 * v4 契约 mock 增补（02 v4.0 🟡 部分：#58/#59、#60-#62、#63/#64、#65-#68、#69-75、#53）
 *
 * 自包含状态 + routeV4 分发；在 routes.ts 主分发的**最前面**挂钩。
 * 未覆盖：health/file 会话的流式问答（落到主分发会返回「mock 未覆盖」提示，
 * SSE 双答案协议的 mock 待后续补）。
 * ============================================================ */

import type { MockResult } from './routes'
import { ok, fail, parseJson } from './routes'

let seq = 9000
const nextId = (): number => ++seq
const now = (): string => {
  const d = new Date()
  const p = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

interface V4Session {
  id: number
  title: string
  kind: 'health' | 'file'
  messageCount: number
  documentCount: number
  processingCount: number
  createTime: string
  updateTime: string
}

const healthSessions: V4Session[] = []
const qaSessions: V4Session[] = []
const qaFiles = new Map<number, Array<{ id: number; title: string; fileType: string; fileSize: number; status: 0 | 1 | 2 | 3; failReason: string | null; chunkCount: number; createTime: string }>>()
const messages = new Map<number, Array<{ id: number; role: 'user' | 'assistant'; content: string; references: unknown[]; createTime: string }>>()
const kvItems: Array<{ id: number; itemKey: string; itemValue: string; source: 'manual' | 'report'; createTime: string; updateTime: string }> = []
const metricRecords: Array<{ id: number; type: string; value: number; value2: number | null; recordedAt: string; note: string | null; source: 'manual' | 'report' }> = []
const drafts = new Map<string, string>()
const customDefs: Array<{ defId: number; key: string; label: string; unit: string | null; valueMin: number | null; valueMax: number | null; hasValue2: boolean; value2Label: string | null; custom: boolean }> = []

const STANDARD: Array<{ key: string; label: string; unit: string; min: number; max: number; has2?: boolean; v2?: string; cat: string }> = [
  { key: 'weight', label: '体重', unit: 'kg', min: 20, max: 300, cat: '体征' },
  { key: 'height', label: '身高', unit: 'cm', min: 80, max: 250, cat: '体征' },
  { key: 'bp', label: '血压', unit: 'mmHg', min: 60, max: 260, has2: true, v2: '舒张压', cat: '血压心肺' },
  { key: 'heart_rate', label: '心率', unit: 'bpm', min: 30, max: 220, cat: '血压心肺' },
  { key: 'fasting_glucose', label: '空腹血糖', unit: 'mmol/L', min: 1, max: 33.3, cat: '代谢' },
  { key: 'sleep', label: '睡眠时长', unit: 'h', min: 0, max: 24, cat: '睡眠运动' },
  { key: 'steps', label: '步数', unit: '步', min: 0, max: 100000, cat: '睡眠运动' }
]

function pushMessage(sessionId: number, role: 'user' | 'assistant', content: string): void {
  const list = messages.get(sessionId) ?? []
  list.push({ id: nextId(), role, content, references: [], createTime: now() })
  messages.set(sessionId, list)
  const s = [...healthSessions, ...qaSessions].find((x) => x.id === sessionId)
  if (s) {
    s.messageCount = list.length
    s.updateTime = now()
  }
}

/** 会话 kind 判定（SSE 分发用）：v4 增补状态里查不到 → 'normal'（主 store 的会话） */
export function kindOfSession(sessionId: number): 'normal' | 'health' | 'file' {
  if (healthSessions.some((s) => s.id === sessionId)) return 'health'
  if (qaSessions.some((s) => s.id === sessionId)) return 'file'
  return 'normal'
}

/** health 会话的文件数（演示态恒 0；file 会话用 qaFiles） */
export function fileCountOf(sessionId: number): number {
  return (qaFiles.get(sessionId) ?? []).length
}

/** 就绪（status=2）文件数：空文件集提问 → 400「请先上传文件」 */
export function readyFileCount(sessionId: number): number {
  return (qaFiles.get(sessionId) ?? []).filter((f) => f.status === 2).length
}

/** 会话消息落库（SSE 完成后调用，保证列表 messageCount 一致） */
export function appendMessage(sessionId: number, role: 'user' | 'assistant', content: string): void {
  pushMessage(sessionId, role, content)
}

export function routeV4(
  method: string,
  pathname: string,
  _query: URLSearchParams,
  rawBody: string
): MockResult | null {
  const body = (): Record<string, unknown> => parseJson(rawBody)

  /* ---------- #58/#59 个人解惑会话 ---------- */
  if (method === 'GET' && pathname === '/api/chat/sessions/health') {
    return ok([...healthSessions].sort((a, b) => b.updateTime.localeCompare(a.updateTime)))
  }
  if (method === 'POST' && pathname === '/api/chat/sessions/health') {
    const title = String(body().title ?? '') || '个人解惑'
    const s: V4Session = { id: nextId(), title, kind: 'health', messageCount: 0, documentCount: 0, processingCount: 0, createTime: now(), updateTime: now() }
    healthSessions.push(s)
    messages.set(s.id, [])
    return ok(s)
  }
  // health 会话的删除/消息/重命名走通用路径前先按 addon 状态处理
  const genericSession = /^\/api\/chat\/sessions\/(\d+)$/.exec(pathname)
  if (genericSession) {
    const id = Number(genericSession[1])
    const owned = healthSessions.find((s) => s.id === id)
    if (owned) {
      if (method === 'DELETE') {
        healthSessions.splice(healthSessions.indexOf(owned), 1)
        messages.delete(id)
        return ok(null)
      }
      if (method === 'PUT') {
        owned.title = String(body().title ?? owned.title)
        owned.updateTime = now()
        return ok(null)
      }
    }
    if (method === 'GET' && (healthSessions.some((s) => s.id === id) || qaSessions.some((s) => s.id === id))) {
      return ok(messages.get(id) ?? [])
    }
  }

  /* ---------- #60-#62 指标目录 ---------- */
  if (method === 'GET' && pathname === '/api/health/metrics/catalog') {
    const cats = new Map<string, unknown[]>()
    for (const m of STANDARD) {
      const list = cats.get(m.cat) ?? []
      list.push({ key: m.key, label: m.label, unit: m.unit, valueMin: m.min, valueMax: m.max, hasValue2: m.has2 ?? false, value2Label: m.v2 ?? null, custom: false })
      cats.set(m.cat, list)
    }
    if (customDefs.length) cats.set('我的自定义', customDefs.map((d) => ({ ...d, defId: d.defId })))
    return ok({ categories: [...cats.entries()].map(([name, metrics]) => ({ name, metrics })) })
  }
  if (method === 'POST' && pathname === '/api/health/metric-defs') {
    if (customDefs.length >= 10) return fail(2006, '自定义指标上限（10 个）')
    const b = body()
    const defId = nextId()
    const def = { defId, key: `c${defId}`, label: String(b.label ?? ''), unit: (b.unit as string) ?? null, valueMin: (b.valueMin as number) ?? null, valueMax: (b.valueMax as number) ?? null, hasValue2: b.hasValue2 === true, value2Label: (b.value2Label as string) ?? null, custom: true }
    customDefs.push(def)
    return ok({ defId: def.defId, key: def.key })
  }
  const defDel = /^\/api\/health\/metric-defs\/(\d+)$/.exec(pathname)
  if (method === 'DELETE' && defDel) {
    const i = customDefs.findIndex((d) => d.defId === Number(defDel[1]))
    if (i < 0) return fail(400, '标准指标不可删除')
    customDefs.splice(i, 1)
    return ok(null)
  }

  /* ---------- #40-#42 指标记录（mock 版直接存 addon，独立于后端联调） ---------- */
  if (pathname === '/api/health/metrics' && method === 'GET') {
    return ok({ records: metricRecords })
  }
  if (method === 'POST' && pathname === '/api/health/metrics') {
    const b = body()
    const v = Number(b.value)
    if (!Number.isFinite(v)) return fail(400, '请填写有效数值')
    const rec = { id: nextId(), type: String(b.type ?? ''), value: v, value2: b.value2 != null ? Number(b.value2) : null, recordedAt: String(b.recordedAt ?? now()), note: (b.note as string) ?? null, source: 'manual' as const }
    metricRecords.push(rec)
    return ok({ id: rec.id })
  }

  /* ---------- #65-#68 健康备忘 KV ---------- */
  if (pathname === '/api/health/kv' && method === 'GET') return ok(kvItems)
  if (method === 'POST' && pathname === '/api/health/kv') {
    if (kvItems.length >= 50) return fail(2006, '健康备忘上限（50 条）')
    const b = body()
    const item = { id: nextId(), itemKey: String(b.itemKey ?? ''), itemValue: String(b.itemValue ?? ''), source: 'manual' as const, createTime: now(), updateTime: now() }
    kvItems.push(item)
    return ok({ id: item.id })
  }
  const kvDel = /^\/api\/health\/kv\/(\d+)$/.exec(pathname)
  if (method === 'DELETE' && kvDel) {
    const i = kvItems.findIndex((k) => k.id === Number(kvDel[1]))
    if (i >= 0) kvItems.splice(i, 1)
    return ok(null)
  }

  /* ---------- #63/#64 报告识读（草稿 10 分钟，简化为固定样例） ---------- */
  if (method === 'POST' && pathname === '/api/health/report/extract') {
    const draftId = `d_${++seq}`
    drafts.set(draftId, now())
    return ok({
      draftId,
      kbDocumentId: null,
      reportDate: now().slice(0, 10),
      items: [
        { key: '血压', value: '128', value2: '82', type: 'metric', metricKey: 'bp', unit: 'mmHg', confidence: 0.91 },
        { key: '空腹血糖', value: '5.6', type: 'metric', metricKey: 'fasting_glucose', unit: 'mmol/L', confidence: 0.9 },
        { key: '医嘱', value: '低盐饮食，三个月后复查', type: 'text', metricKey: null, unit: null, confidence: 0.83 }
      ]
    })
  }
  if (method === 'POST' && pathname === '/api/health/report/commit') {
    const b = body()
    if (!drafts.has(String(b.draftId))) return fail(400, '草稿已过期，请重新上传')
    drafts.delete(String(b.draftId))
    const items = (b.items as Array<{ type: string }>) ?? []
    return ok({ metricCount: items.filter((i) => i.type === 'metric').length, kvCount: items.filter((i) => i.type === 'text').length, kbDocumentId: null })
  }

  /* ---------- #69-#75 文档解惑 ---------- */
  if (method === 'POST' && pathname === '/api/knowledge/qa/sessions') {
    const s: V4Session = { id: nextId(), title: String(body().title ?? '') || '文档解惑', kind: 'file', messageCount: 0, documentCount: 0, processingCount: 0, createTime: now(), updateTime: now() }
    qaSessions.push(s)
    qaFiles.set(s.id, [])
    messages.set(s.id, [])
    return ok(s)
  }
  if (method === 'GET' && pathname === '/api/knowledge/qa/sessions') {
    return ok([...qaSessions].sort((a, b) => b.updateTime.localeCompare(a.updateTime)))
  }
  const qaDocs = /^\/api\/knowledge\/qa\/sessions\/(\d+)\/documents$/.exec(pathname)
  if (qaDocs) {
    const id = Number(qaDocs[1])
    const s = qaSessions.find((x) => x.id === id)
    if (!s) return fail(404, '解惑会话不存在')
    if (method === 'POST') {
      const docId = nextId()
      const list = qaFiles.get(id) ?? []
      list.push({ id: docId, title: `上传文件 ${list.length + 1}`, fileType: 'txt', fileSize: 1024, status: 2, failReason: null, chunkCount: 3, createTime: now() })
      qaFiles.set(id, list)
      s.documentCount = list.length
      s.updateTime = now()
      return ok({ documentId: docId, status: 2 })
    }
    if (method === 'GET') return ok(qaFiles.get(id) ?? [])
  }
  const qaDocDel = /^\/api\/knowledge\/qa\/documents\/(\d+)$/.exec(pathname)
  if (method === 'DELETE' && qaDocDel) return ok(null)
  const qaSessDel = /^\/api\/knowledge\/qa\/sessions\/(\d+)$/.exec(pathname)
  if (method === 'DELETE' && qaSessDel) {
    const i = qaSessions.findIndex((x) => x.id === Number(qaSessDel[1]))
    if (i >= 0) qaSessions.splice(i, 1)
    return ok(null)
  }

  /* ---------- #53 个人库设置（单开关已在主分发？兜底处理） ---------- */
  if (method === 'PUT' && pathname === '/api/user/kb/settings') return ok(null)

  return null
}
