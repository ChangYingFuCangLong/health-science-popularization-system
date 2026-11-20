/* ============================================================
 * 轻量全局提示（自建，不依赖 Element Plus）
 *
 * 为什么不用 ElMessage：
 *   前端设计.md §7.1 约定「用户端不引 Element Plus 组件」，
 *   更关键的是——若共享的 message 工具 import 了 ElMessage，
 *   Element Plus 会被打进【主 chunk】，管理端的懒加载收益直接归零
 *   （Element Plus 约 900KB）。所以提示必须自建。
 *
 * 形态：屏幕居中轻提示（2026-09-20 交互裁决，渲染在 ToastHost.vue）——
 *   比顶部提示更醒目；仍保持轻提示原则：非模态、可点击关闭、自动消失。
 * ============================================================ */

import { reactive, readonly } from 'vue'

export type ToastType = 'info' | 'success' | 'warning' | 'error'

export interface Toast {
  id: number
  type: ToastType
  text: string
}

const DURATION_MS = 2600

const state = reactive<{ items: Toast[] }>({ items: [] })
let seq = 0

export const toasts = readonly(state)

export function dismissToast(id: number): void {
  const idx = state.items.findIndex((t) => t.id === id)
  if (idx >= 0) state.items.splice(idx, 1)
}

export function showMessage(text: string, type: ToastType = 'info'): void {
  const id = ++seq
  state.items.push({ id, type, text })
  window.setTimeout(() => dismissToast(id), DURATION_MS)
}

/** 清空（切换账号 / 退出登录时调用，避免残留提示跨账号显示） */
export function clearToasts(): void {
  state.items.splice(0, state.items.length)
}
