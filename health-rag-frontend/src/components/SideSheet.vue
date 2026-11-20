<script setup lang="ts">
/* ============================================================
 * 通用侧栏 / 底部抽屉外壳
 *
 * 移动端从底部升起、桌面端从右侧滑出——这是本项目所有"面板类"交互的
 * 统一形态（分块预览、画像编辑都用它）。形态一致，用户只学一次。
 *
 * 抽成组件而不是各写一份的原因：遮罩点击、ESC 关闭、打开时锁定背景滚动、
 * 两种方向的两套过渡——这四件事任何一处漏掉都是"看起来能用、用起来别扭"的
 * 缺陷（曾出现过只做了遮罩没做 ESC 的版本）。职责收在一处，就不可能只改一半。
 *
 * 无障碍：role=dialog + aria-modal + 可聚焦的关闭入口（由调用方在 header 里提供）。
 * 移动端的抓手是纯装饰，关闭靠按钮 / 遮罩 / ESC，不依赖手势。
 * ============================================================ */

import { onBeforeUnmount, watch } from 'vue'

const props = defineProps<{
  open: boolean
  /** 无障碍标签（读屏播报的面板名） */
  label: string
  /** 桌面端面板宽度，缺省 480px */
  width?: string
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

let prevOverflow = ''
let locked = false

function lockScroll(): void {
  if (locked) return
  prevOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  locked = true
}

function unlockScroll(): void {
  if (!locked) return
  document.body.style.overflow = prevOverflow
  locked = false
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      lockScroll()
      window.addEventListener('keydown', onKeydown)
    } else {
      unlockScroll()
      window.removeEventListener('keydown', onKeydown)
    }
  }
)

onBeforeUnmount(() => {
  unlockScroll()
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Transition name="sheet">
    <div
      v-if="open"
      class="fixed inset-0 z-50 flex max-md:items-end md:justify-end"
      role="dialog"
      aria-modal="true"
      :aria-label="label"
    >
      <div class="absolute inset-0 bg-black/40" @click="emit('close')" />

      <div
        class="relative flex w-full flex-col border-line bg-canvas
               max-md:max-h-[85dvh] max-md:rounded-t-card max-md:border-t
               md:h-full md:border-l md:max-w-[var(--sheet-max-w)]"
        :style="{ '--sheet-max-w': width ?? '480px' }"
      >
        <div
          class="mx-auto mt-2 h-1 w-10 shrink-0 rounded-full bg-line-strong md:hidden"
          aria-hidden="true"
        />
        <slot />
      </div>
    </div>
  </Transition>
</template>

<style scoped>
/* 遮罩淡入 + 面板滑入：两个元素、两个方向、两个时长（手册：场景过渡 300~400ms） */
.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.26s ease;
}
.sheet-enter-active > div:last-child,
.sheet-leave-active > div:last-child {
  transition: transform 0.32s cubic-bezier(0.16, 1, 0.3, 1);
}
.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}
/* 移动端从下往上，桌面端从右往左 */
.sheet-enter-from > div:last-child,
.sheet-leave-to > div:last-child {
  transform: translateY(100%);
}
@media (min-width: 768px) {
  .sheet-enter-from > div:last-child,
  .sheet-leave-to > div:last-child {
    transform: translateX(100%);
  }
}
@media (prefers-reduced-motion: reduce) {
  .sheet-enter-active,
  .sheet-leave-active,
  .sheet-enter-active > div:last-child,
  .sheet-leave-active > div:last-child {
    transition: none;
  }
}
</style>
