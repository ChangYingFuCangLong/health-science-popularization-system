/* ============================================================
 * 健康档案状态（v2 新模块「健康中心」）
 *
 * 双模式设计（与 useAdminList 的 404 降级语义一脉相承）：
 *   cloud —— 后端 /health/metrics 已就绪，数据入库，跨设备同步
 *   local —— 后端未实现（业务码 404），静默降级为本机 localStorage，
 *            功能完整可用；后端就绪后下次加载自动切换，前端代码零改动
 *
 * unknown 表示尚未完成首次探测。
 * ============================================================ */

import { computed, ref } from 'vue'
import type { CreateMetricPayload, HealthMetric } from '@/api/health'
import { readWithLegacy } from '@/utils/storage'

type MetricType = string
import { createHealthMetric, deleteHealthMetric, listHealthMetrics } from '@/api/health'

const STORAGE_KEY = 'health-rag-health-metrics'
const LEGACY_STORAGE_KEY = 'qian-health-metrics'

export type HealthMode = 'unknown' | 'cloud' | 'local'

const mode = ref<HealthMode>('unknown')
const records = ref<HealthMetric[]>([])
const loading = ref(false)

function readLocal(): HealthMetric[] {
  try {
    const raw = readWithLegacy(STORAGE_KEY, LEGACY_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as HealthMetric[]) : []
  } catch {
    return []
  }
}

function writeLocal(list: HealthMetric[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list))
  } catch {
    /* 存储不可用时数据仍保留在本次会话内 */
  }
}

function sortByTime(list: HealthMetric[]): HealthMetric[] {
  return [...list].sort((a, b) => a.recordedAt.localeCompare(b.recordedAt))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await listHealthMetrics({ days: 90 }, { silent: true })
    records.value = sortByTime(res.records ?? [])
    mode.value = 'cloud'
  } catch {
    // 云端未实现（404）或网络故障：一律先落本机，下次加载继续探测云端
    records.value = sortByTime(readLocal())
    mode.value = 'local'
  } finally {
    loading.value = false
  }
}

async function add(payload: CreateMetricPayload): Promise<void> {
  if (mode.value === 'cloud') {
    const res = await createHealthMetric(payload, { silent: true })
    records.value = sortByTime([
      ...records.value,
      {
        id: res.id,
        type: payload.type,
        value: payload.value,
        value2: payload.value2 ?? null,
        recordedAt: payload.recordedAt ?? '',
        note: payload.note ?? null
      }
    ])
    return
  }

  // local 模式（含云端首次探测失败的场景：本地先落，下次加载自动探测云端）
  const local: HealthMetric = {
    id: Date.now(),
    type: payload.type,
    value: payload.value,
    value2: payload.value2 ?? null,
    recordedAt: payload.recordedAt ?? '',
    note: payload.note ?? null
  }
  const next = sortByTime([...readLocal(), local])
  writeLocal(next)
  records.value = next
  if (mode.value === 'unknown') mode.value = 'local'
}

async function remove(id: number): Promise<void> {
  if (mode.value === 'cloud') {
    await deleteHealthMetric(id, { silent: true })
    records.value = records.value.filter((r) => r.id !== id)
    return
  }
  const next = readLocal().filter((r) => r.id !== id)
  writeLocal(next)
  records.value = next
}

function byType(type: MetricType): HealthMetric[] {
  return records.value.filter((r) => r.type === type)
}

/** 某类型的最新一条（按 recordedAt） */
function latest(type: MetricType): HealthMetric | null {
  const list = byType(type)
  return list.length ? list[list.length - 1] : null
}

export function useHealthMetrics() {
  return {
    mode,
    records,
    loading,
    load,
    add,
    remove,
    byType,
    latest,
    byTypeSorted: computed(() => (type: MetricType) => byType(type))
  }
}
