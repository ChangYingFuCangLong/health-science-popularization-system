<script setup lang="ts">
/* ============================================================
 * RAG 阶段时间线（v2 创新点 1：检索过程可视化 · F-3 契合版）
 *
 * 数据来源（按优先级，全部真实协议事件、无模拟）：
 *   1. V2 后端显式 stage 事件（B-12）：retrieve_start / retrieve_done(rewrittenQuery) / generate_start
 *   2. V1 后端：reference 事件到达 = 检索完成（兜底推断，行为与 v2.0 一致）
 *
 * 两种形态：
 *   完整时间线 —— 生成中逐步点亮（青 = 检索，珊瑚 = 生成）
 *   折叠摘要   —— 完成后收成一行（微交互②：折叠转场），保留可解释性信息
 * ============================================================ */

import { computed } from 'vue'
import type { MessageStage } from '@/stores/chat'

const props = withDefaults(
  defineProps<{
    stage: MessageStage
    /** 检索是否已完成（stage.retrieve_done 或 reference 置位） */
    retrieved?: boolean
    referenceCount: number
    retrieveMs: number | null
    /** 回答总耗时（done 后由折叠摘要展示） */
    latencyMs?: number | null
    /** 首字耗时（客户端测量；服务端 firstTokenMs 在 done 里，二选一展示） */
    firstDeltaMs?: number | null
    /** 改写后的检索词（V2 M10；缺省不展示） */
    rewrittenQuery?: string | null
  }>(),
  { retrieved: false, latencyMs: null, firstDeltaMs: null, rewrittenQuery: null }
)

const STEPS = [
  { key: 'understand', label: '理解问题', tint: 'brand' },
  { key: 'retrieve', label: '检索知识库', tint: 'brand' },
  { key: 'generate', label: '生成回答', tint: 'accent' }
] as const

type StepState = 'done' | 'active' | 'todo'

/** 每一步状态：retrieved 标志优先于当前活跃步（retrieve_done 与 generate_start 之间有真实间隙） */
const states = computed<StepState[]>(() => {
  const s = props.stage
  const retrieveDone = props.retrieved || s === 'generate' || s === 'done'
  return [
    s === 'understand' ? 'active' : 'done',
    retrieveDone ? 'done' : s === 'retrieve' ? 'active' : 'todo',
    s === 'done' ? 'done' : s === 'generate' ? 'active' : 'todo'
  ]
})

const collapsed = computed(() => props.stage === 'done')

const retrieveText = computed(() => {
  if (!props.retrieved) return ''
  const seconds = props.retrieveMs != null ? ` · ${(props.retrieveMs / 1000).toFixed(1)}s` : ''
  return `${props.referenceCount} 个来源${seconds}`
})

/** 折叠摘要：一条可解释性信息行 */
const summaryText = computed(() => {
  const parts: string[] = []
  if (props.retrieved || props.referenceCount > 0) {
    parts.push(`检索 ${props.referenceCount} 源`)
  }
  if (props.retrieveMs != null) parts.push(`检索耗时 ${(props.retrieveMs / 1000).toFixed(1)}s`)
  const first = props.firstDeltaMs
  if (first != null) parts.push(`首字 ${(first / 1000).toFixed(1)}s`)
  if (props.latencyMs != null && props.latencyMs > 0) {
    parts.push(`回答用时 ${(props.latencyMs / 1000).toFixed(1)}s`)
  }
  return parts.join(' · ')
})
</script>

<template>
  <!-- 折叠摘要（stage=done）：微交互② 由 Transition 折叠转场呈现 -->
  <div v-if="collapsed" class="stage-collapse mb-2">
    <p class="flex flex-wrap items-center gap-x-2 text-hint text-ink-muted/80">
      <span class="inline-flex items-center gap-1">
        <span class="h-1.5 w-1.5 rounded-full bg-brand" aria-hidden="true" />
        {{ summaryText || '已完成' }}
      </span>
      <span
        v-if="rewrittenQuery"
        class="rounded-full border border-line bg-surface px-2 py-0.5"
      >
        改写检索词：{{ rewrittenQuery }}
      </span>
    </p>
  </div>

  <!-- 完整时间线（生成中） -->
  <ol
    v-else
    class="stage-collapse mb-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-hint"
    aria-label="回答生成进度"
  >
    <li v-for="(step, i) in STEPS" :key="step.key" class="flex items-center gap-1.5">
      <span
        class="relative flex h-2 w-2 items-center justify-center rounded-full"
        :class="[
          states[i] === 'done'
            ? step.tint === 'accent'
              ? 'bg-accent'
              : 'bg-brand'
            : states[i] === 'active'
              ? step.tint === 'accent'
                ? 'bg-accent stage-pulse'
                : 'bg-brand stage-pulse'
              : 'bg-line-strong'
        ]"
      />
      <span
        :class="
          states[i] === 'todo'
            ? 'text-ink-muted/60'
            : step.tint === 'accent' && states[i] !== 'done'
              ? 'text-accent'
              : states[i] === 'done'
                ? 'text-ink-muted'
                : 'text-brand'
        "
      >
        {{ step.label }}
      </span>

      <span v-if="step.key === 'retrieve' && retrieveText" class="text-brand">
        · {{ retrieveText }}
      </span>

      <span v-if="i < STEPS.length - 1" class="ml-1 h-px w-4 bg-line-strong" aria-hidden="true" />

      <span
        v-if="step.key === 'retrieve' && rewrittenQuery && states[i] !== 'todo'"
        class="ml-1 rounded-full border border-line bg-surface px-2 py-0.5 text-ink-muted"
      >
        改写检索词：{{ rewrittenQuery }}
      </span>
    </li>
  </ol>
</template>

<style scoped>
.stage-pulse {
  animation: stage-breathe 1s ease-in-out infinite;
}
@keyframes stage-breathe {
  0%,
  100% {
    box-shadow: 0 0 0 0 currentColor;
    opacity: 1;
  }
  50% {
    box-shadow: 0 0 0 4px transparent;
    opacity: 0.55;
  }
}
</style>
