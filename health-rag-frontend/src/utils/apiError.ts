/* ============================================================
 * HTTP 错误模型 —— 纯函数，不依赖 store / router
 *
 * 为什么必须显式处理：fetch 在 4xx/5xx 时【不会 reject】，
 * res.ok 为 false 而 res.body 是 JSON 错误体。
 * 不判断的话，SSE 解析器会把错误静默吞掉，界面永远停在"生成中"。
 * ============================================================ */

import type { R } from '@/api/types'

/** 后端业务错误 / HTTP 错误统一表示 */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number

  constructor(code: number, message: string, httpStatus = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

/**
 * 把非 2xx 响应转成 ApiError。
 * 后端统一返回 { code, message, data }，优先取 body 里的 code；
 * body 不是该结构（网关页、代理错误）时回退到 HTTP 状态码。
 */
export async function toApiError(res: Response): Promise<ApiError> {
  const fallback = mapHttpStatus(res.status)
  let code = res.status
  let message = fallback

  try {
    const text = await res.text()
    if (text) {
      const body = JSON.parse(text) as Partial<R>
      if (typeof body.code === 'number') code = body.code
      if (typeof body.message === 'string' && body.message) message = body.message
    }
  } catch {
    // body 非 JSON：保留按状态码推导的默认文案
  }

  return new ApiError(code, message, res.status)
}

function mapHttpStatus(status: number): string {
  switch (status) {
    case 400:
      return '请求参数有误'
    case 401:
      return '登录状态已失效，请重新登录'
    case 403:
      return '无访问权限'
    case 404:
      return '资源不存在'
    case 429:
      return '请求过于频繁，请稍后重试'
    default:
      return status >= 500 ? '服务异常，请稍后重试' : '请求失败'
  }
}

/**
 * 用户主动中止（点「停止」）触发的是 AbortError，不是故障。
 * 必须与真实错误区分，否则用户点停止反而会看到报错提示。
 */
export function isAbortError(e: unknown): boolean {
  return (e as { name?: string } | null)?.name === 'AbortError'
}

/** 是否属于需要走登录态处理的错误 */
export function isAuthError(e: unknown): boolean {
  if (!(e instanceof ApiError)) return false
  return e.code === 401 || e.httpStatus === 401 || e.code === 403 || e.httpStatus === 403
}
