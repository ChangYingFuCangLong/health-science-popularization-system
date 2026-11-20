<script setup lang="ts">
/* 骨架屏 v2：微光扫描（shimmer），消除刷新时的白屏与 CLS */
</script>

<template>
  <div class="space-y-5" aria-hidden="true">
    <div v-for="i in 3" :key="i" :class="i % 2 ? 'mr-16' : 'ml-16'">
      <div class="shimmer h-3 w-24 rounded-full" />
      <div class="shimmer mt-2 h-20 rounded-bubble border border-line" />
    </div>
  </div>
</template>
