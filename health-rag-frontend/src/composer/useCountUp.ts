/* ============================================================
 * 数字 count-up（微交互③：健康中心录入反馈）
 * 目标值变化时，展示值用 rAF 从旧值平滑滚到新值。
 * 纯展示层动效；prefers-reduced-motion 由全局 CSS 豁免不了 JS——
 * 所以这里直接读媒体查询，命中时跳过动画直接落值。
 * ============================================================ */

import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

export function useCountUp(
  getTarget: () => number | null,
  duration = 500
): Ref<number | null> {
  const displayed = ref<number | null>(getTarget())
  let raf: number | null = null

  function stop(): void {
    if (raf != null) {
      cancelAnimationFrame(raf)
      raf = null
    }
  }

  watch(getTarget, (next) => {
    stop()
    if (next == null) {
      displayed.value = null
      return
    }
    const from = displayed.value ?? next
    if (from === next) {
      displayed.value = next
      return
    }

    const reduce =
      typeof window !== 'undefined' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (reduce) {
      displayed.value = next
      return
    }

    const startAt = performance.now()
    const step = (now: number): void => {
      const t = Math.min(1, (now - startAt) / duration)
      const eased = 1 - Math.pow(1 - t, 3)
      displayed.value = from + (next - from) * eased
      if (t < 1) {
        raf = requestAnimationFrame(step)
      } else {
        raf = null
        displayed.value = next
      }
    }
    raf = requestAnimationFrame(step)
  })

  onBeforeUnmount(stop)
  return displayed
}
