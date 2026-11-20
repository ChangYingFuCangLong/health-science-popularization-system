<script setup lang="ts">
/* 环境底色：双温区光晕（青 + 珊瑚），呼应「生命体征」主题。
 * 纯 CSS 实现，blur 巨大化后 GPU 负担可控；reduced-motion 下静止。 */
</script>

<template>
  <div class="pointer-events-none fixed inset-0 -z-10 overflow-hidden" aria-hidden="true">
    <div
      class="blob absolute -top-40 -left-32 h-[34rem] w-[34rem] rounded-full opacity-[var(--blob-opacity)] blur-[110px]"
      style="background: radial-gradient(circle, #14b8a6 0%, transparent 65%); animation: blob-drift 34s ease-in-out infinite alternate"
    />
    <div
      class="blob absolute top-1/3 -right-40 h-[30rem] w-[30rem] rounded-full opacity-[calc(var(--blob-opacity)*0.8)] blur-[110px]"
      style="background: radial-gradient(circle, #f97316 0%, transparent 65%); animation: blob-drift 42s ease-in-out infinite alternate-reverse"
    />
    <div
      class="blob blob-c absolute -bottom-48 left-1/4 h-[28rem] w-[28rem] rounded-full opacity-[calc(var(--blob-opacity)*0.5)] blur-[110px]"
      style="background: radial-gradient(circle, #0d9488 0%, transparent 65%); animation: blob-drift 50s ease-in-out infinite alternate"
    />
  </div>
</template>

<style scoped>
/* 流畅度兜底：移动端 GPU 内存有限，缩小模糊半径并去掉第三个光晕。
   transform 动画本身走合成器，代价主要在首次栅格化的模糊层内存。 */
@media (max-width: 767px) {
  .blob {
    filter: blur(70px);
  }
  .blob-c {
    display: none;
  }
}
</style>
