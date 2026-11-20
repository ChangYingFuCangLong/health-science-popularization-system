<script setup lang="ts">
/* 生成中的三点动画。
 * 动效在 main.css 的 prefers-reduced-motion 媒体查询下会被统一关闭（前端设计.md §10）。 */
</script>

<template>
  <span class="flex items-center gap-1" role="status" aria-label="正在生成">
    <span
      v-for="i in 3"
      :key="i"
      class="h-1.5 w-1.5 animate-bounce rounded-full bg-ink-muted"
      :style="{ animationDelay: `${(i - 1) * 0.15}s` }"
    />
  </span>
</template>
