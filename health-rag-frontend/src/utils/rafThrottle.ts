/* ============================================================
 * 合帧节流（ADR-016）
 * 依据：docs/前端设计.md §5.3
 *
 * 为什么需要：
 *   delta 是【逐 token】推送的，一段 300 字的回答约 300~600 次事件。
 *   若每次都触发 Vue 响应式更新 + markdown-it 全量重解析，复杂度是 O(n²)——
 *   回答越长越卡。这不是"优化一下更好"，而是长回答必然掉帧。
 *
 * 做法：把一帧（约 16ms）内累积的增量合并成一次写入。
 *
 * 两个必须注意的边界：
 *   1. 标签页隐藏时浏览器会暂停 rAF，pending 会累积 —— 这没问题，
 *      因为 done 事件回传了完整全文 content，前端用它校正一次即可。
 *   2. done 时必须调用 flush()，否则最后几个字符会丢。
 * ============================================================ */

export interface RafThrottleOptions {
  /** 便于单测注入手动调度器；默认 requestAnimationFrame */
  schedule?: (cb: () => void) => number
  cancel?: (handle: number) => void
}

export interface RafThrottle<T> {
  /** 追加一项，将在下一帧统一提交 */
  push(item: T): void
  /** 立即提交所有待处理项（完成时必须调用） */
  flush(): void
  /** 待处理项数量，供调试与断言 */
  pendingCount(): number
}

export function createRafThrottle<T>(
  apply: (items: T[]) => void,
  options: RafThrottleOptions = {}
): RafThrottle<T> {
  const schedule =
    options.schedule ??
    ((cb: () => void) => requestAnimationFrame(() => cb()))
  const cancel = options.cancel ?? ((handle: number) => cancelAnimationFrame(handle))

  let pending: T[] = []
  let handle: number | null = null

  function commit(): void {
    handle = null
    if (pending.length === 0) return
    const items = pending
    pending = []
    apply(items)
  }

  return {
    push(item: T): void {
      pending.push(item)
      if (handle === null) {
        handle = schedule(commit)
      }
    },

    flush(): void {
      if (handle !== null) {
        cancel(handle)
        handle = null
      }
      commit()
    },

    pendingCount(): number {
      return pending.length
    }
  }
}
