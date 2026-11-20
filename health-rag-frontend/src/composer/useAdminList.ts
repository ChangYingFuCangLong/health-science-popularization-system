import { ref } from 'vue'
import type { PageResult } from '@/api/types'
import { ApiError } from '@/utils/apiError'

/* ============================================================
 * 管理端列表页的通用取数逻辑
 *
 * 解决的是「并行开发」下的真实问题：后端 M8 尚未实现，
 * 管理端接口当前全部返回业务码 404。若每个页面各自处理，
 * 会出现 8 份不一致的降级逻辑和 8 种不同的报错表现。
 *
 * 统一约定：
 *   - 404  → `unavailable = true`：接口未实现，显示空态说明（不报错、不刷屏）
 *   - 其他 → `errorMsg`：真实故障，显示可重试的错误条
 *
 * 后端实现后，页面无需改动即可正常显示数据。
 * ============================================================ */

export interface AdminListOptions {
  /** 每页条数，默认 20 */
  pageSize?: number
  /** 是否在创建时立即加载 */
  immediate?: boolean
}

export function useAdminList<T>(
  fetcher: (params: { pageNum: number; pageSize: number }) => Promise<PageResult<T>>,
  options: AdminListOptions = {}
) {
  const items = ref<T[]>([]) as { value: T[] }
  const total = ref(0)
  const pageNum = ref(1)
  const pageSize = ref(options.pageSize ?? 20)
  const loading = ref(false)
  const unavailable = ref(false)
  const errorMsg = ref('')

  async function reload(): Promise<void> {
    loading.value = true
    errorMsg.value = ''
    try {
      const page = await fetcher({ pageNum: pageNum.value, pageSize: pageSize.value })
      items.value = page.records ?? []
      total.value = page.total ?? 0
      unavailable.value = false
    } catch (e) {
      items.value = []
      total.value = 0
      // 业务码 404 = 后端尚未实现该接口；其余才是真故障
      if (e instanceof ApiError && e.code === 404) {
        unavailable.value = true
      } else {
        errorMsg.value = e instanceof Error ? e.message : '加载失败'
      }
    } finally {
      loading.value = false
    }
  }

  function onPageChange(p: number): void {
    pageNum.value = p
    void reload()
  }

  function onSizeChange(s: number): void {
    pageSize.value = s
    pageNum.value = 1
    void reload()
  }

  if (options.immediate !== false) void reload()

  return {
    items,
    total,
    pageNum,
    pageSize,
    loading,
    unavailable,
    errorMsg,
    reload,
    onPageChange,
    onSizeChange
  }
}

/**
 * 单次取数（概览、汇总这类非分页接口），降级语义同上。
 */
export function useAdminResource<T>(fetcher: () => Promise<T>) {
  const data = ref<T | null>(null)
  const loading = ref(false)
  const unavailable = ref(false)
  const errorMsg = ref('')

  async function reload(): Promise<void> {
    loading.value = true
    errorMsg.value = ''
    try {
      data.value = await fetcher()
      unavailable.value = false
    } catch (e) {
      data.value = null
      if (e instanceof ApiError && e.code === 404) {
        unavailable.value = true
      } else {
        errorMsg.value = e instanceof Error ? e.message : '加载失败'
      }
    } finally {
      loading.value = false
    }
  }

  void reload()

  return { data, loading, unavailable, errorMsg, reload }
}
