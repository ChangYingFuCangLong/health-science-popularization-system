<script setup lang="ts">
/* ============================================================
 * 追问建议（对话 2.0 · F-4 契合版）
 * 内容来源已在 MessageItem 决定（服务端 §4.9 优先，客户端派生兜底），
 * 本组件只负责展示。
 * ============================================================ */

defineProps<{ items: string[] }>()
const emit = defineEmits<{ send: [text: string] }>()
</script>

<template>
  <div v-if="items.length" class="mt-2 flex flex-wrap gap-1.5" aria-label="追问建议">
    <button
      v-for="q in items"
      :key="q"
      type="button"
      class="rounded-full border border-line bg-canvas px-3 py-1 text-hint text-ink-muted transition-colors hover:border-brand hover:text-brand"
      @click="emit('send', q)"
    >
      {{ q }}
    </button>
  </div>
</template>
