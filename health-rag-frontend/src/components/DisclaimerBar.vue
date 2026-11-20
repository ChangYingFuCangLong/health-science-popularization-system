<script setup lang="ts">
/* ============================================================
 * 免责声明 —— 常驻，不可关闭（前端设计.md §11.1）
 *
 * 必要性：技术栈选型.md §9.4 要求「会话界面固定展示」。
 * 健康类产品若无免责声明，专业性无从谈起。
 *
 * 位置：固定在输入框下方一行小字，不随消息滚动。
 * ============================================================ */

const DEFAULT_TEXT =
  '本系统内容基于公开健康科普语料检索生成，仅供科普参考，不能替代医生的诊断与处方。如有不适请及时就医。'

defineProps<{ text?: string }>()
</script>

<template>
  <p class="px-4 pb-2 pt-1 text-center text-hint leading-relaxed text-ink-muted">
    {{ text ?? DEFAULT_TEXT }}
  </p>
</template>
