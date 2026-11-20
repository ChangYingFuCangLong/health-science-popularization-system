<script setup lang="ts">
/* 主题切换：暗「夜间诊室」/ 亮「晨间诊室」 */
import { useTheme } from '@/composer/useTheme'

const { mode, toggle } = useTheme()
</script>

<template>
  <button
    type="button"
    class="flex h-8 w-8 items-center justify-center rounded-btn border border-line text-ink-muted transition-colors hover:border-brand hover:text-brand"
    :aria-label="mode === 'dark' ? '切换到亮色模式' : '切换到暗色模式'"
    @click="toggle"
  >
    <svg v-if="mode === 'dark'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
    <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
    </svg>
  </button>
</template>
