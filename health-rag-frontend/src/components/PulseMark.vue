<script setup lang="ts">
/* ============================================================
 * 脉搏线 —— 品牌签名（「生命体征 · Vital」）
 *
 * 一段心电波形：空闲时缓慢描线呼吸（呼吸感 = 系统在线）；
 * live=true（流式生成中）时描线加速并发光 = 系统心跳加快。
 * SVG stroke 动画，无 JS 循环。
 * ============================================================ */

withDefaults(defineProps<{ live?: boolean; width?: number }>(), {
  live: false,
  width: 96
})
</script>

<template>
  <svg
    :width="width"
    :height="width * 0.25"
    viewBox="0 0 96 24"
    fill="none"
    role="img"
    aria-label="系统脉搏"
  >
    <defs>
      <linearGradient id="pulse-grad" x1="0" y1="0" x2="96" y2="0" gradientUnits="userSpaceOnUse">
        <stop offset="0" stop-color="#14b8a6" />
        <stop offset="1" stop-color="#fb923c" />
      </linearGradient>
    </defs>
    <path
      d="M0 12 H26 L32 12 L36 5 L42 19 L46 9 L50 12 H64 L68 12 L72 3 L78 21 L82 12 H96"
      stroke="url(#pulse-grad)"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      :class="live ? 'pulse-path pulse-live' : 'pulse-path'"
    />
  </svg>
</template>

<style scoped>
.pulse-path {
  stroke-dasharray: 150;
  animation: pulse-draw 3.2s linear infinite;
  opacity: 0.85;
}
.pulse-live {
  animation-duration: 1.1s;
  opacity: 1;
  filter: drop-shadow(0 0 5px rgba(251, 146, 60, 0.6));
}
</style>
