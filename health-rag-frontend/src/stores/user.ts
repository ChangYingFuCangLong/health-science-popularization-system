/* ============================================================
 * 用户状态（前端设计.md §4）
 *
 * 持久化策略（关键设计）：
 *   token     持久化（localStorage）—— 刷新保持登录
 *   userInfo  不持久化 —— 每次刷新重新拉取 /api/auth/me。
 *             理由：role 决定管理端入口是否显示，若读本地缓存，
 *             管理员被降级后前端仍显示管理入口（点进去才 403），体验很差。
 *             代价是一次请求，换来状态永远准确。
 * ============================================================ */

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { UserInfo } from '@/api/types'
import * as authApi from '@/api/auth'

export const useUserStore = defineStore(
  'user',
  () => {
    /* ---------------- state ---------------- */
    const token = ref<string | null>(null)
    const userInfo = ref<UserInfo | null>(null)

    /* ---------------- getters ---------------- */
    const isLoggedIn = computed(() => token.value !== null)
    const isAdmin = computed(() => userInfo.value?.role === 'ADMIN')
    const displayName = computed(
      () => userInfo.value?.nickname || userInfo.value?.username || ''
    )

    /* ---------------- actions ---------------- */

    async function login(username: string, password: string): Promise<void> {
      const result = await authApi.login(username, password)
      token.value = result.token
      userInfo.value = result.userInfo
    }

    async function fetchMe(): Promise<void> {
      userInfo.value = await authApi.fetchMe()
    }

    async function logout(): Promise<void> {
      try {
        await authApi.logout()
      } catch {
        // 服务端黑名单失败不阻塞本地登出——本地态必须先清，保证体验确定性
      }
      clear()
    }

    function clear(): void {
      token.value = null
      userInfo.value = null
    }

    return {
      token,
      userInfo,
      isLoggedIn,
      isAdmin,
      displayName,
      login,
      fetchMe,
      logout,
      clear
    }
  },
  {
    // 只持久化 token；userInfo 明确不持久化（见文件头注释）
    persist: { pick: ['token'] }
  }
)
