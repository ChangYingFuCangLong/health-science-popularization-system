<script setup lang="ts">
/* ============================================================
 * 个人知识库 · 上传投放区
 *
 * 设计要点：
 *   1. 拖拽投放（桌面）与点击选择（全端）双通道；移动端拖拽不可用，
 *      因此按钮必须始终可见且触摸目标 ≥44px —— 不能只靠拖拽这一条路。
 *   2. 空态时它是页面主角（大投放区 + 引导文案），有资料后退居为一条细栏，
 *      避免长期占用首屏。同一个组件两种形态，靠 `compact` 切换。
 *   3. 客户端预校验（类型/空文件/超限）在【选择文件的那一刻】就给反馈，
 *      不等到请求回来 —— 20MB 的文件传完再告诉他格式不对，是在浪费用户时间。
 *
 * 无障碍：整块区域不是可聚焦控件（避免读屏念一堆无意义内容），
 * 真正的可聚焦入口是内部按钮，键盘用户走按钮这条路。
 * ============================================================ */

import { computed, ref } from 'vue'
import { ALLOWED_ACCEPT, formatSize, MAX_FILE_SIZE, validateFile } from '@/api/userKb'
import { UPLOAD_TYPES_LABEL } from '@/constants/upload'

const props = defineProps<{
  /** 上传中：百分比 0~100；-1 表示进度未知 */
  uploadPercent?: number | null
  /** 紧凑形态（已有资料时） */
  compact?: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'pick', file: File): void
  (e: 'error', message: string): void
}>()

const inputEl = ref<HTMLInputElement | null>(null)
/** 拖拽进入计数：子元素间移动会连续触发 enter/leave，只用布尔量会闪 */
const dragDepth = ref(0)
const dragging = computed(() => dragDepth.value > 0)
const uploading = computed(() => props.uploadPercent != null)

function openPicker(): void {
  if (props.disabled || uploading.value) return
  inputEl.value?.click()
}

function accept(file: File | undefined | null): void {
  if (!file) return
  const err = validateFile(file)
  if (err) {
    emit('error', err)
    return
  }
  emit('pick', file)
}

function onInputChange(e: Event): void {
  const input = e.target as HTMLInputElement
  accept(input.files?.[0])
  // 同一个文件连续选择两次也要能触发 change
  input.value = ''
}

function onDragEnter(e: DragEvent): void {
  if (props.disabled || uploading.value) return
  e.preventDefault()
  dragDepth.value += 1
}

function onDragOver(e: DragEvent): void {
  if (props.disabled || uploading.value) return
  // 必须 preventDefault，否则浏览器会用「打开文件」接管这次投放
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDragLeave(): void {
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}

function onDrop(e: DragEvent): void {
  e.preventDefault()
  dragDepth.value = 0
  if (props.disabled || uploading.value) return
  accept(e.dataTransfer?.files?.[0])
}
</script>

<template>
  <div
    class="relative rounded-card border-2 border-dashed transition-colors duration-200"
    :class="[
      dragging ? 'border-brand bg-brand-soft' : 'border-line bg-surface',
      uploading ? 'border-brand bg-surface' : '',
      compact ? 'px-4 py-3' : 'px-6 py-10'
    ]"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <input
      ref="inputEl"
      type="file"
      class="sr-only"
      :accept="ALLOWED_ACCEPT"
      :disabled="disabled"
      @change="onInputChange"
    />

    <!-- 上传中：把整个投放区变成进度读数，避免用户在传输中重复点击 -->
    <div v-if="uploading" class="flex items-center gap-3">
      <svg
        class="shrink-0 animate-spin text-brand"
        width="18"
        height="18"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        aria-hidden="true"
      >
        <path d="M21 12a9 9 0 1 1-6.2-8.6" />
      </svg>
      <div class="min-w-0 flex-1">
        <p class="text-body text-ink">正在上传…</p>
        <div class="mt-1.5 h-1 overflow-hidden rounded-full bg-surface-2">
          <!-- 进度未知（-1）时走不定长的流动条，不假装一个数字 -->
          <div
            v-if="uploadPercent === -1"
            class="shimmer h-full w-1/3 rounded-full"
          />
          <div
            v-else
            class="h-full rounded-full bg-brand transition-[width] duration-200"
            :style="{ width: `${uploadPercent}%` }"
          />
        </div>
      </div>
      <span v-if="uploadPercent !== -1" class="tnum shrink-0 text-body text-brand">
        {{ uploadPercent }}%
      </span>
    </div>

    <!-- 紧凑形态 -->
    <div v-else-if="compact" class="flex items-center gap-3">
      <svg
        class="shrink-0 text-ink-muted"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M12 5v14M5 12h14" />
      </svg>
      <p class="min-w-0 flex-1 truncate text-hint text-ink-muted">
        拖入文件，或
        <span class="text-brand">选择一个文件</span> —— 支持
        {{ UPLOAD_TYPES_LABEL }}，单个不超过 20 MB
      </p>
      <button
        type="button"
        class="shrink-0 rounded-btn border border-line px-3 py-2 text-body text-ink transition-colors hover:border-brand hover:text-brand disabled:opacity-40 max-md:min-h-11"
        :disabled="disabled"
        @click="openPicker"
      >
        选择文件
      </button>
    </div>

    <!-- 空态：投放区升格为主角 -->
    <div v-else class="flex flex-col items-center text-center">
      <span
        class="flex h-12 w-12 items-center justify-center rounded-full border border-line bg-surface-2 text-ink-muted"
        aria-hidden="true"
      >
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M14 3v4a1 1 0 0 0 1 1h4" />
          <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
          <path d="M12 12v5M9.5 14.5 12 12l2.5 2.5" />
        </svg>
      </span>
      <p class="mt-3 text-section font-medium text-ink">
        {{ dragging ? '松开即可上传' : '把你的资料交给它' }}
      </p>
      <p class="mt-1 max-w-md text-hint text-ink-muted">
        上传后系统会解析、分块并向量化，形成<span class="text-ink">只属于你</span>的检索层。
        它叠在权威知识库之上参与问答，不会改动管理端的语料，也不对其他人可见。
      </p>
      <button
        type="button"
        class="mt-5 rounded-btn grad-primary px-5 py-2.5 text-body transition-transform hover:scale-[1.02] active:scale-[0.98] disabled:opacity-40 disabled:hover:scale-100 max-md:min-h-11"
        :disabled="disabled"
        @click="openPicker"
      >
        选择文件上传
      </button>
      <p class="mt-3 text-hint text-ink-muted/80">
        支持 {{ UPLOAD_TYPES_LABEL }} · 单个不超过
        {{ formatSize(MAX_FILE_SIZE) }} · 扫描版 PDF 无文本层，暂不支持
      </p>
    </div>
  </div>
</template>
