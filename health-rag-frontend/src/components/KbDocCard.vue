<script setup lang="ts">
/* ============================================================
 * 个人知识库 · 文档卡片
 *
 * 一张卡片承载四种状态（待处理 / 处理中 / 已完成 / 失败），
 * 每种状态给出【下一步能做什么】而不只是状态名：
 *   处理中 → 显示真实进度（done/total）而不是转圈；
 *   失败   → 直接把原因写出来 + 一键重试（不要让用户猜）；
 *   已完成 → 可查看分块、可重新索引；
 *   全部   → 可删除（两步确认）。
 *
 * 两步确认沿用会话侧栏的既有约定：首次点击变成「确认删除」，3 秒后自动复位。
 * 这是全站统一的破坏性操作语言，用户不需要重新学一遍。
 * ============================================================ */

import { computed, onBeforeUnmount, ref } from 'vue'
import { DOC_STATUS_LABEL, formatSize } from '@/api/userKb'
import type { PersonalDocProgress, PersonalKbDoc } from '@/api/userKb'

const props = defineProps<{
  doc: PersonalKbDoc
  progress?: PersonalDocProgress | null
  /** 该卡片上有请求在飞（重建/删除），用于禁用重复点击 */
  busy?: boolean
  /** 入场错峰序号，用于编排式淡入 */
  index?: number
}>()

const emit = defineEmits<{
  (e: 'preview', id: number): void
  (e: 'rebuild', id: number): void
  (e: 'remove', id: number): void
}>()

const confirmSince = ref(0)
let confirmTimer: number | null = null

const confirming = computed(() => confirmSince.value > 0)

function armConfirm(): void {
  if (confirmTimer !== null) window.clearTimeout(confirmTimer)
  confirmSince.value = Date.now()
  confirmTimer = window.setTimeout(() => {
    confirmSince.value = 0
    confirmTimer = null
  }, 3000)
}

function onRemove(): void {
  if (!confirming.value) {
    armConfirm()
    return
  }
  if (confirmTimer !== null) window.clearTimeout(confirmTimer)
  confirmTimer = null
  confirmSince.value = 0
  emit('remove', props.doc.id)
}

onBeforeUnmount(() => {
  if (confirmTimer !== null) window.clearTimeout(confirmTimer)
})

/* ---------------- 展示派生 ---------------- */

/** 百分数仅在有真实 total 时给出——total 未知时给 0% 是假信息 */
const percent = computed<number | null>(() => {
  const p = props.progress
  if (!p || !p.total || p.total <= 0) return null
  return Math.min(100, Math.round((p.done / p.total) * 100))
})

const statusTone = computed(() => {
  switch (props.doc.status) {
    case 1:
      return 'text-brand'
    case 2:
      return 'text-success'
    case 3:
      return 'text-danger'
    default:
      return 'text-ink-muted'
  }
})

/** 失败态卡片整体降噪（虚线边），避免用红色描边把页面搞得很吵 */
const cardClass = computed(() =>
  props.doc.status === 3
    ? 'border-dashed border-danger/40 bg-surface'
    : 'border-line bg-surface'
)

const enterStyle = computed(() => {
  const i = props.index ?? 0
  return { animationDelay: `${Math.min(i, 8) * 40}ms` }
})
</script>

<template>
  <article
    class="fade-up rounded-card border p-4"
    :class="cardClass"
    :style="enterStyle"
  >
    <div class="flex items-start gap-3">
      <!-- 文件类型徽标：等宽字形 + 全大写，与「仪表读数」的既有视觉语言一致 -->
      <span
        class="font-display tnum mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-btn border border-line bg-surface-2 text-[10px] uppercase tracking-wide text-ink-muted"
        aria-hidden="true"
      >
        {{ doc.fileType }}
      </span>

      <div class="min-w-0 flex-1">
        <h3 class="truncate text-body font-medium text-ink" :title="doc.title">
          {{ doc.title }}
        </h3>
        <p class="mt-0.5 flex flex-wrap items-center gap-x-2 text-hint text-ink-muted">
          <span class="tnum">{{ formatSize(doc.fileSize) }}</span>
          <span v-if="doc.chunkCount > 0" class="tnum">· {{ doc.chunkCount }} 段</span>
          <span class="tnum">· {{ doc.createTime }}</span>
        </p>
      </div>

      <span class="flex shrink-0 items-center gap-1.5 text-hint" :class="statusTone">
        <span
          v-if="doc.status === 1"
          class="h-1.5 w-1.5 animate-pulse rounded-full bg-current"
          aria-hidden="true"
        />
        {{ DOC_STATUS_LABEL[doc.status] }}
      </span>
    </div>

    <!-- 处理中：真实进度 -->
    <div v-if="doc.status === 1" class="mt-3">
      <div class="flex items-center justify-between text-hint text-ink-muted">
        <span>解析并向量化中</span>
        <span class="tnum">
          <template v-if="progress">{{ progress.done }} / {{ progress.total ?? '…' }}</template>
          <template v-else>…</template>
        </span>
      </div>
      <div class="mt-1.5 h-1 overflow-hidden rounded-full bg-surface-2">
        <div
          v-if="percent === null"
          class="shimmer h-full w-1/3 rounded-full"
        />
        <div
          v-else
          class="h-full rounded-full bg-brand transition-[width] duration-300"
          :style="{ width: `${percent}%` }"
        />
      </div>
    </div>

    <!-- 失败：把原因写出来，并给出唯一有意义的下一个动作 -->
    <p v-else-if="doc.status === 3" class="mt-2 text-hint text-ink-muted">
      {{ doc.failReason || '处理失败，原因未知' }}
    </p>

    <!-- 操作 -->
    <div class="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-3">
      <button
        v-if="doc.status === 2"
        type="button"
        class="rounded-btn border border-line px-3 py-1.5 text-hint text-ink-muted transition-colors hover:border-brand hover:text-brand disabled:opacity-40 max-md:min-h-11 max-md:flex-1"
        :disabled="busy"
        @click="emit('preview', doc.id)"
      >
        查看分块
      </button>

      <button
        v-if="doc.status === 2 || doc.status === 3"
        type="button"
        class="rounded-btn border border-line px-3 py-1.5 text-hint text-ink-muted transition-colors hover:border-brand hover:text-brand disabled:opacity-40 max-md:min-h-11 max-md:flex-1"
        :disabled="busy"
        @click="emit('rebuild', doc.id)"
      >
        {{ doc.status === 3 ? '重试' : '重新索引' }}
      </button>

      <button
        type="button"
        class="ml-auto rounded-btn px-3 py-1.5 text-hint transition-colors disabled:opacity-40 max-md:min-h-11"
        :class="
          confirming
            ? 'bg-danger/10 font-medium text-danger'
            : 'text-danger/70 hover:text-danger'
        "
        :disabled="busy"
        @click="onRemove"
      >
        {{ confirming ? '确认删除' : '删除' }}
      </button>
    </div>
  </article>
</template>
