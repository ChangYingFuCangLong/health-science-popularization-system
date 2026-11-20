/* ============================================================
 * 401 / 403 的唯一处理实现（前端设计.md §6.4）
 *
 * 背景：v1.0 的一致性漏洞——第八章只定义了 Axios 拦截器处理 401，
 * 但 SSE 走原生 fetch，完全绕过拦截器。结果是 token 过期时普通接口
 * 正常跳登录页，流式问答却毫无反应——而流式恰恰是最常用的接口。
 *
 * 因此：Axios 响应拦截器与 SSE 客户端【各调一次本函数】，不允许第二份实现。
 *
 * 注：本文件 import 了 router 与 store。与 router → guard → store → api →
 * request → 本文件 构成 ESM 环，但所有引用都发生在函数体内（运行时已就绪），
 * 不在模块顶层求值，因此安全。若未来出现顶层使用，需改为 main.ts 注入。
 * ============================================================ */

import router from '@/router'
import { useUserStore } from '@/stores/user'
import { clearToasts, showMessage } from '@/utils/message'
import type { ApiError } from '@/utils/apiError'
import { isAuthError } from '@/utils/apiError'

/**
 * 处理认证类错误。
 * @returns 是否已处理（true 表示调用方无需再提示 / 再抛出）
 */
export function handleAuthError(e: unknown): boolean {
  if (!isAuthError(e)) return false

  const err = e as ApiError
  const unauthorized = err.code === 401 || err.httpStatus === 401

  if (unauthorized) {
    useUserStore().clear()
    clearToasts()
    void router.replace({
      path: '/login',
      query: { redirect: router.currentRoute.value.fullPath }
    })
  } else {
    // 403：轻提示，不跳转
    showMessage('无访问权限', 'warning')
  }
  return true
}
