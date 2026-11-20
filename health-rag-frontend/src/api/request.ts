/* ============================================================
 * Axios 实例与统一拦截（前端设计.md §8.1）
 * 契约：后端统一返回 { code, message, data }，code === 200 时业务层直接拿 data。
 * ============================================================ */

import axios from 'axios'
import type { AxiosError, AxiosRequestConfig, AxiosResponse } from 'axios'
import type { R } from '@/api/types'
import { useUserStore } from '@/stores/user'
import { ApiError as ApiErrorClass } from '@/utils/apiError'
import { handleAuthError } from '@/utils/authError'
import { showMessage } from '@/utils/message'

/**
 * 单请求的可选行为。
 *
 * `silent: true` 表示「失败是预期内的」：拦截器照常解析并抛出错误，
 * 但**不弹全局提示**，由调用方自行降级渲染。
 *
 * 典型场景：后端按里程碑交付，某些接口（如 M5 的知识浏览）在某阶段尚未实现，
 * 返回 404。此时页面应显示空态，而不是一进页面连弹几个错误提示刷屏。
 */
export interface RequestOptions {
  silent?: boolean
}

/** axios 允许在 config 上挂自定义字段 */
interface SilentConfig extends AxiosRequestConfig {
  silent?: boolean
}

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE,
  timeout: 15000
})

/* 请求拦截器：注入 token */
request.interceptors.request.use((config) => {
  const token = useUserStore().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/* 响应拦截器：解包统一响应体 */
request.interceptors.response.use(
  // 这里用 unknown 作为响应体类型：解包后的实际类型由 get/post/… 的泛型 T 承担
  (response: AxiosResponse<unknown>) => {
    const body = response.data as R

    if (body?.code === 200) {
      // 业务层直接拿 data，无需再写 .data.data
      response.data = body.data
      return response
    }

    const err = new ApiErrorClass(body.code, body.message || '请求失败')
    const silent = (response.config as SilentConfig | undefined)?.silent
    if (!silent && !handleAuthError(err)) {
      showMessage(err.message, 'error')
    }
    return Promise.reject(err)
  },

  (error: AxiosError) => {
    // 后端经网关返回的非 2xx：优先读 body 里的 code/message
    const status = error.response?.status ?? 0
    const body = error.response?.data as Partial<R> | undefined

    let code = typeof body?.code === 'number' ? body.code : status
    let message = body?.message || httpMessage(status)

    // F-2：429 限流统一文案（02 §1.5），与 401/403 通道分离，绝不触发跳登录
    if (status === 429 || code === 429) {
      code = 429
      message = '请求过于频繁，请稍后再试'
    }

    const err = new ApiErrorClass(code, message, status)

    const silent = (error.config as SilentConfig | undefined)?.silent
    if (!silent && !handleAuthError(err)) {
      showMessage(err.message, 'error')
    }
    return Promise.reject(err)
  }
)

function httpMessage(status: number): string {
  if (status === 0) return '网络异常，请稍后重试'
  if (status === 429) return '请求过于频繁，请稍后再试'
  if (status >= 500) return '服务异常，请稍后重试'
  return '请求失败'
}

/* ---------------- 类型安全的快捷方法 ---------------- */

export async function get<T>(
  url: string,
  params?: object,
  options?: RequestOptions
): Promise<T> {
  const config: SilentConfig = {
    params: params as Record<string, unknown> | undefined
  }
  if (options?.silent) config.silent = true
  const res = await request.get<T>(url, config)
  return res.data
}

/** post 的可选行为：silent 静默 + onUploadProgress 上传进度（multipart 大文件用） */
export interface PostOptions extends RequestOptions {
  /** 0~100；浏览器未提供 total 时回调 -1（不定进度） */
  onUploadProgress?: (percent: number) => void
}

export async function post<T>(url: string, body?: unknown, options?: PostOptions): Promise<T> {
  const config: SilentConfig = {}
  if (options?.silent) config.silent = true
  if (options?.onUploadProgress) {
    config.onUploadProgress = (e: { loaded?: number; total?: number }) => {
      const total = e.total ?? 0
      options.onUploadProgress!(total > 0 ? Math.round(((e.loaded ?? 0) / total) * 100) : -1)
    }
  }
  const res = await request.post<T>(url, body, config)
  return res.data
}

export async function put<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> {
  const config: SilentConfig = {}
  if (options?.silent) config.silent = true
  const res = await request.put<T>(url, body, config)
  return res.data
}

export async function del<T>(url: string, options?: RequestOptions): Promise<T> {
  const config: SilentConfig = {}
  if (options?.silent) config.silent = true
  const res = await request.delete<T>(url, config)
  return res.data
}

export default request
