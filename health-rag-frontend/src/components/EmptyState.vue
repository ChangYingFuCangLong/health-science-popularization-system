<script setup lang="ts">
/* ============================================================
 * 空态 v2 —— 品牌时刻
 * 签名元素：大幅脉搏线缓慢描线（系统在线的呼吸感）。
 * 文案直接说差异化能力：回答带出处（RAG 可溯源）。
 * ============================================================ */

import { ref } from 'vue'
import PulseMark from '@/components/PulseMark.vue'

const emit = defineEmits<{ pick: [text: string] }>()

const EXAMPLES = ref([
  '高血压患者日常饮食需要注意什么？',
  '每天运动多久比较合适？',
  '长期熬夜会有哪些危害？',
  '如何判断自己的睡眠质量？'
])
</script>

<template>
  <div class="flex h-full flex-col items-center justify-center px-6 pb-10 text-center">
    <PulseMark :width="300" class="mb-6 opacity-90" />

    <h1 class="grad-text msg-in text-[26px] font-semibold leading-snug">问健康，答有出处</h1>
    <p class="msg-in mt-3 max-w-chat text-body text-ink-muted" style="animation-delay: 60ms">
      每个回答都检索自健康科普语料，并标注引用来源与相关度。
      <br class="hidden sm:block" />
      内容仅供科普参考，不能替代医生诊断。
    </p>

    <div class="mt-8 grid w-full max-w-chat gap-2 sm:grid-cols-2">
      <button
        v-for="(example, i) in EXAMPLES"
        :key="example"
        type="button"
        class="card-lift msg-in rounded-card border border-line bg-surface px-4 py-3 text-left text-body text-ink"
        :style="{ animationDelay: `${120 + i * 60}ms` }"
        @click="emit('pick', example)"
      >
        {{ example }}
      </button>
    </div>
  </div>
</template>
