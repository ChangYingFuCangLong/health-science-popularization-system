<script setup lang="ts">
/* ============================================================
 * 数据看板 v2 —— 「仪器面板」（ECharts 按需引入）
 *
 * 相比 v1 的升级：
 *   - 问答趋势折线、热门问题条形图（数据来自概览接口已有的
 *     trend / hotQuestions 字段，零新接口）
 *   - 检索策略对比雷达图（Hit@5 / MRR / 忠实度，来自评测汇总）
 *     —— 论文消融实验结论的可视化出口
 * 数据缺失 / 接口未就绪时沿用 StatePanel 降级。
 * ============================================================ */

import { computed, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import * as adminApi from '@/api/admin'
import { useAdminResource } from '@/composer/useAdminList'
import { useChart, chartColor } from '@/composer/useChart'
import { useTheme } from '@/composer/useTheme'
import StatePanel from '@/components/admin/StatePanel.vue'

const { mode: themeMode } = useTheme()

const overview = useAdminResource(() => adminApi.getOverview({ silent: true }))
const vectors = useAdminResource(() => adminApi.getVectorMeta({ silent: true }))
const summary = useAdminResource(() => adminApi.getEvalSummary({}, { silent: true }))

/**
 * ⚠️ 后端 OverviewVO 只有 avgLatencyMs（整体响应耗时均值），
 * 没有首字延迟均值（avgFirstTokenMs 待后端补充，见接口需求文档）。
 */
function latencyLevel(ms: number): 'success' | 'warning' | 'danger' {
  if (ms <= 8000) return 'success'
  if (ms <= 15000) return 'warning'
  return 'danger'
}

/* ---------------- 图表 ---------------- */

const trendEl = ref<HTMLElement | null>(null)
const hotEl = ref<HTMLElement | null>(null)
const radarEl = ref<HTMLElement | null>(null)

const trendOption = computed<EChartsCoreOption | null>(() => {
  const trend = overview.data.value?.trend ?? []
  if (trend.length === 0) return null
  return {
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: chartColor('--c-surface-2'),
      borderColor: chartColor('--c-line'),
      textStyle: { color: chartColor('--c-ink'), fontSize: 12 }
    },
    xAxis: {
      type: 'category',
      data: trend.map((t) => t.date.slice(5)),
      axisLabel: { color: chartColor('--c-ink-muted'), fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: chartColor('--c-ink-muted') },
      splitLine: { lineStyle: { color: chartColor('--c-line') } }
    },
    series: [
      {
        type: 'line',
        data: trend.map((t) => t.count),
        smooth: true,
        symbolSize: 6,
        lineStyle: { color: chartColor('--c-brand'), width: 2.5 },
        itemStyle: { color: chartColor('--c-brand') },
        areaStyle: { color: 'rgba(20,184,166,0.12)' }
      }
    ]
  }
})

const hotOption = computed<EChartsCoreOption | null>(() => {
  const hot = overview.data.value?.hotQuestions ?? []
  if (hot.length === 0) return null
  return {
    grid: { left: 8, right: 30, top: 8, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: chartColor('--c-surface-2'),
      borderColor: chartColor('--c-line'),
      textStyle: { color: chartColor('--c-ink'), fontSize: 12 }
    },
    xAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--c-ink-muted') }, splitLine: { lineStyle: { color: chartColor('--c-line') } } },
    yAxis: {
      type: 'category',
      inverse: true,
      data: hot.map((h) => (h.question.length > 12 ? `${h.question.slice(0, 12)}…` : h.question)),
      axisLabel: { color: chartColor('--c-ink-muted'), fontSize: 11 }
    },
    series: [
      {
        type: 'bar',
        data: hot.map((h) => h.count),
        barMaxWidth: 14,
        itemStyle: {
          borderRadius: 7,
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 1, y2: 0,
            colorStops: [
              { offset: 0, color: '#14b8a6' },
              { offset: 1, color: '#fb923c' }
            ]
          }
        }
      }
    ]
  }
})

/** 雷达指标统一换算成百分制；数值异常时跳过该维度 */
function toPct(v: number | null | undefined): number | null {
  if (v == null || !Number.isFinite(v)) return null
  return Math.round((v <= 1 ? v * 100 : v) * 10) / 10
}

const radarOption = computed<EChartsCoreOption | null>(() => {
  const items = summary.data.value?.items ?? []
  const usable = items.filter((i) => toPct(i.hitRate5) != null)
  if (usable.length === 0) return null
  return {
    tooltip: {
      backgroundColor: chartColor('--c-surface-2'),
      borderColor: chartColor('--c-line'),
      textStyle: { color: chartColor('--c-ink'), fontSize: 12 }
    },
    legend: {
      bottom: 0,
      textStyle: { color: chartColor('--c-ink-muted'), fontSize: 11 }
    },
    radar: {
      indicator: [
        { name: 'Hit@5', max: 100 },
        { name: 'MRR', max: 100 },
        { name: '忠实度', max: 100 }
      ],
      radius: '62%',
      axisName: { color: chartColor('--c-ink-muted') },
      splitLine: { lineStyle: { color: chartColor('--c-line') } },
      splitArea: { show: false },
      axisLine: { lineStyle: { color: chartColor('--c-line') } }
    },
    series: [
      {
        type: 'radar',
        data: usable.map((item, i) => ({
          name: strategyText(item.strategy),
          value: [toPct(item.hitRate5), toPct(item.mrr), toPct(item.avgFaithfulness)],
          areaStyle: { opacity: 0.15 },
          lineStyle: { width: 2 },
          itemStyle: { color: ['#14b8a6', '#fb923c', '#a78bfa'][i % 3] }
        }))
      }
    ]
  }
})

useChart(trendEl, () => trendOption.value, [trendOption, themeMode])
useChart(hotEl, () => hotOption.value, [hotOption, themeMode])
useChart(radarEl, () => radarOption.value, [radarOption, themeMode])

function strategyText(s: string): string {
  if (s === 'naive') return '向量检索'
  if (s === 'hybrid') return '混合检索'
  if (s === 'hybrid_rerank') return '混合+重排'
  return s ?? '-'
}
</script>

<template>
  <div>
    <h2 class="mb-4 text-xl font-medium text-gray-800">数据看板</h2>

    <StatePanel
      :loading="overview.loading.value"
      :unavailable="overview.unavailable.value"
      :error-msg="overview.errorMsg.value"
      @retry="overview.reload"
    >
      <!-- KPI 行 -->
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-xs text-gray-400">用户数</p>
          <p class="tnum mt-1 text-2xl font-semibold text-gray-800">
            {{ overview.data.value?.totalUsers ?? 0 }}
          </p>
        </div>
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-xs text-gray-400">会话数</p>
          <p class="tnum mt-1 text-2xl font-semibold text-gray-800">
            {{ overview.data.value?.totalSessions ?? 0 }}
          </p>
        </div>
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-xs text-gray-400">问答数</p>
          <p class="tnum mt-1 text-2xl font-semibold text-gray-800">
            {{ overview.data.value?.totalMessages ?? 0 }}
          </p>
        </div>
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-xs text-gray-400">今日问答</p>
          <p class="tnum mt-1 text-2xl font-semibold text-gray-800">
            {{ overview.data.value?.todayMessages ?? 0 }}
          </p>
        </div>
      </div>

      <!-- 图表行：趋势 + 热门问题 -->
      <div class="mt-4 grid gap-4 lg:grid-cols-2">
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-sm text-gray-500">问答趋势</p>
          <div v-if="!trendOption" class="flex h-56 items-center justify-center text-sm text-gray-400">
            暂无趋势数据
          </div>
          <div v-else ref="trendEl" class="h-56" />
        </div>
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-sm text-gray-500">热门问题</p>
          <div v-if="!hotOption" class="flex h-56 items-center justify-center text-sm text-gray-400">
            暂无热门问题数据
          </div>
          <div v-else ref="hotEl" class="h-56" />
        </div>
      </div>

      <!-- 指标行 -->
      <div class="mt-4 grid gap-4 lg:grid-cols-3">
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-sm text-gray-500">响应耗时</p>
          <div class="mt-2">
            <el-tag :type="latencyLevel(overview.data.value?.avgLatencyMs ?? 0)">
              完整回答 {{ overview.data.value?.avgLatencyMs ?? 0 }} ms
            </el-tag>
            <span class="ml-2 text-xs text-gray-400">≤ 15000 ms</span>
          </div>
          <!-- F-1：首字延迟均值（NFR 首字 ≤3s 的直接证据）；后端 M9 起提供 -->
          <div v-if="overview.data.value?.avgFirstTokenMs != null" class="mt-2">
            <el-tag
              :type="(overview.data.value.avgFirstTokenMs ?? 0) <= 3000 ? 'success' : 'danger'"
            >
              首字 {{ overview.data.value.avgFirstTokenMs }} ms
            </el-tag>
            <span class="ml-2 text-xs text-gray-400">≤ 3000 ms</span>
          </div>
          <div v-else class="mt-1 text-xs text-gray-400">
            首字延迟均值后端暂未提供（待补 avgFirstTokenMs）
          </div>
        </div>
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-sm text-gray-500">拒答率</p>
          <p class="tnum mt-2 text-2xl text-gray-800">
            {{ ((overview.data.value?.rejectRate ?? 0) * 100).toFixed(1) }}%
          </p>
        </div>
        <div class="rounded-card border border-gray-200 bg-white p-4">
          <p class="text-sm text-gray-500">语料 / 分块</p>
          <p class="tnum mt-2 text-2xl text-gray-800">
            {{ overview.data.value?.docCount ?? 0 }}
            <span class="text-base text-gray-400">/ {{ overview.data.value?.chunkCount ?? 0 }}</span>
          </p>
        </div>
      </div>
    </StatePanel>

    <!-- 策略对比雷达（消融实验可视化） -->
    <h3 class="mt-8 mb-3 text-lg font-medium text-gray-800">检索策略对比</h3>
    <StatePanel
      :loading="summary.loading.value"
      :unavailable="summary.unavailable.value"
      :error-msg="summary.errorMsg.value"
      :empty="(summary.data.value?.items ?? []).length === 0"
      pending-tip="评测汇总接口后端尚未实现"
      empty-tip="暂无汇总数据（需先在「评测集管理」运行评测）"
      @retry="summary.reload"
    >
      <div class="rounded-card border border-gray-200 bg-white p-4">
        <div class="flex flex-col items-center gap-4 lg:flex-row">
          <div ref="radarEl" class="h-72 w-full lg:w-1/2" />
          <div class="w-full text-xs text-gray-400 lg:w-1/2">
            Hit@5：前 5 条检索结果命中标准出处的比例；MRR：命中位次的倒数均值；
            忠实度：回答与检索证据的一致性。
          </div>
        </div>
      </div>
    </StatePanel>

    <!-- 向量库 -->
    <h3 class="mt-8 mb-4 text-lg font-medium text-gray-800">向量库</h3>
    <StatePanel
      :loading="vectors.loading.value"
      :unavailable="vectors.unavailable.value"
      :error-msg="vectors.errorMsg.value"
      pending-tip="向量元信息接口后端尚未实现"
      @retry="vectors.reload"
    >
      <el-descriptions :column="2" border>
        <el-descriptions-item label="模型">
          {{ vectors.data.value?.embeddingModel ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="维度">
          {{ vectors.data.value?.dimension ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="向量总数">
          {{ vectors.data.value?.chunkTotal ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="构建时间">
          {{ vectors.data.value?.builtAt ?? '-' }}
        </el-descriptions-item>
      </el-descriptions>
    </StatePanel>
  </div>
</template>
