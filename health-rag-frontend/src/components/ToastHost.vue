<script setup lang="ts">
import { toasts, dismissToast } from '@/composer/useToast'
import type { ToastType } from '@/composer/useToast'

/* 全局轻提示：屏幕居中显示（交互裁决）——比顶部提示更醒目，
 * 移动端拇指区之外不遮挡输入；仍保持轻提示原则：不模态、可点击关闭、
 * 2.6s 自动消失（useToast.ts）。TransitionGroup：弹入、淡出、平滑让位。 */
const styles: Record<ToastType, string> = {
  info: 'border-line',
  success: 'border-brand-strong',
  warning: 'border-warning',
  error: 'border-danger'
}
</script>

<template>
  <TransitionGroup
    tag="div"
    name="toast"
    class="pointer-events-none fixed inset-0 z-[9999] flex flex-col items-center justify-center gap-2 px-4"
  >
    <div
      v-for="item in toasts.items"
      :key="item.id"
      class="pointer-events-auto max-w-full cursor-pointer rounded-btn border bg-surface px-4 py-2 text-body text-ink shadow-sm"
      :class="styles[item.type]"
      role="status"
      aria-live="polite"
      @click="dismissToast(item.id)"
    >
      {{ item.text }}
    </div>
  </TransitionGroup>
</template>

<style scoped>
.toast-enter-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.toast-leave-active {
  /* 绝对定位使其脱离流，让下方提示平滑上移（TransitionGroup move） */
  position: absolute;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  pointer-events: none;
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.toast-enter-from {
  opacity: 0;
  transform: translateY(-10px) scale(0.96);
}
.toast-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
.toast-move {
  transition: transform 0.18s ease;
}
</style>
