/* ============================================================
 * ECharts 组合式封装（v2 引入，服务健康中心与管理端看板）
 *
 * 按需注册：只引入用到的图表与组件，避免全量打包（≈ 1MB → ~300KB）。
 * 职责：初始化 / 响应式更新 / ResizeObserver 自适应 / 卸载销毁。
 * 主题适配：调用方把 useTheme().mode 传入 deps，切换主题时重渲染，
 *           颜色在 getOption 里实时读 CSS 变量，不在 JS 里写死色值。
 * ============================================================ */

import { onBeforeUnmount, watch, type Ref } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, RadarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'

echarts.use([
  LineChart,
  BarChart,
  RadarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer
])

/** 从当前主题的 CSS 变量取图表用色（保持图表与界面同一套令牌） */
export function chartColor(name: '--c-brand' | '--c-accent' | '--c-ink' | '--c-ink-muted' | '--c-line' | '--c-surface-2'): string {
  if (typeof window === 'undefined') return '#888'
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || '#888'
}

/**
 * @param el        容器 ref（可能由 v-if 延迟出现，出现时自动初始化）
 * @param getOption 返回当前配置；返回 null 表示清空
 * @param deps      参与响应的额外依赖（数据源 / 主题等）
 */
export function useChart(
  el: Ref<HTMLElement | null>,
  getOption: () => EChartsCoreOption | null,
  deps: ReadonlyArray<Ref<unknown>> = []
): void {
  let chart: echarts.ECharts | null = null
  let observer: ResizeObserver | null = null

  function ensure(): void {
    if (chart || !el.value) return
    chart = echarts.init(el.value)
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(el.value)
  }

  function render(): void {
    const option = getOption()
    if (!option) {
      chart?.clear()
      return
    }
    ensure()
    // notMerge：数据集变化时整体替换，避免旧 series 残留
    chart?.setOption(option, true)
  }

  watch([el, ...deps], render)

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
    chart?.dispose()
    chart = null
  })
}
