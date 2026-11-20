/* ============================================================
 * 自动朗读开关（F-5：04-裁决 §4.2 输出增强）
 *
 * 开启后，回答生成完成时自动用系统 TTS 朗读（零后端、离线可用）。
 * 偏好持久化 localStorage；触发逻辑在 ChatView（监听流式结束），
 * 本模块只管开关状态。
 * ============================================================ */

import { ref } from 'vue'
import { readWithLegacy } from '@/utils/storage'

const KEY = 'health-rag-auto-read'
const LEGACY_KEY = 'qian-auto-read'

function initial(): boolean {
  return readWithLegacy(KEY, LEGACY_KEY) === '1'
}

const enabled = ref(initial())

export function useAutoRead() {
  return {
    enabled,
    toggle(): void {
      enabled.value = !enabled.value
      try {
        localStorage.setItem(KEY, enabled.value ? '1' : '0')
      } catch {
        /* 持久化失败不影响本次会话 */
      }
    }
  }
}
