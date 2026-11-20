/* ============================================================
 * 主题切换（Vital v2）
 *
 * 机制：.dark 类挂在 <html> 上，所有颜色令牌随之翻转（见 tokens.css）。
 * 初始主题由 index.html 内联脚本在挂载前设置（默认暗色，避免闪烁），
 * 本模块只在首次使用时读取当前状态，并提供切换与持久化。
 * ============================================================ */

import { ref } from 'vue'
import { migrateKey } from '@/utils/storage'

const KEY = 'health-rag-theme'
migrateKey(KEY, 'qian-theme')

export type ThemeMode = 'light' | 'dark'

const mode = ref<ThemeMode>(
  typeof document !== 'undefined' && document.documentElement.classList.contains('dark')
    ? 'dark'
    : 'light'
)

function apply(next: ThemeMode): void {
  mode.value = next
  document.documentElement.classList.toggle('dark', next === 'dark')
  try {
    localStorage.setItem(KEY, next)
  } catch {
    /* 隐私模式等场景下持久化失败不影响本次会话 */
  }
}

export function useTheme() {
  return {
    mode,
    toggle(): void {
      apply(mode.value === 'dark' ? 'light' : 'dark')
    }
  }
}
