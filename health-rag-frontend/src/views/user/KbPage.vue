<script setup lang="ts">
/* 我的资料库（个人健康科普 · 二级页 /health/kb，11 号 §〇）
 * 个人知识库全功能面板在此归位；本页只是路由壳与页头。 */
import MyKbPanel from '@/components/MyKbPanel.vue'
</script>

<template>
  <div class="mx-auto max-w-list px-4 py-6">
    <div class="mb-4 flex items-center justify-between gap-3">
      <div>
        <h1 class="text-display font-semibold text-ink">我的资料库</h1>
        <p class="mt-1 text-hint text-ink-muted">
          上传你的健康资料，个人解惑将合并检索这里的内容（引用会标注来源）。
        </p>
      </div>
      <router-link
        to="/health"
        class="shrink-0 rounded-btn border border-line px-3 py-1.5 text-body text-ink-muted hover:border-brand hover:text-brand"
      >
        ← 返回概览
      </router-link>
    </div>
    <MyKbPanel />
  </div>
</template>
