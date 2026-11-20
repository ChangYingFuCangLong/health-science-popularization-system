/* ============================================================
 * 账号设置接口层（02 v4.0 §三，#5/#6/#45）
 *
 * 设置模块（/profile）= 纯账号：资料修改 / 改密 / 注销。
 * 画像、指标等个人数据的管理在个人健康科普模块（/health/**）。
 * ============================================================ */

import { post, put } from '@/api/request'
import type { RequestOptions } from '@/api/request'

export interface UpdateProfilePayload {
  nickname?: string
  email?: string
  avatar?: string
}

/** #5 修改资料（非 null 才更新） */
export function updateProfile(
  payload: UpdateProfilePayload,
  options?: RequestOptions
): Promise<null> {
  return put<null>('/user/profile', payload, options)
}

/** #6 修改密码（错误 1004；成功即全部旧 token 失效） */
export function updatePassword(oldPassword: string, newPassword: string): Promise<null> {
  return put<null>('/user/password', { oldPassword, newPassword })
}

/**
 * #45 注销账号：body { password }（1004 校验）；ADMIN 不可注销 → 400。
 * 副作用（02 v4.0 §十一）：deleted=true + 踢下线 + 健康五表与个人资料库/文档解惑
 * 个人资产物理删，审计数据（普通会话/日志）保留、username 置空「已注销用户」。
 *
 * ⚠️ 后端实为 **POST /api/auth/account/deactivate**（AuthController），
 * 不是 `DELETE /user/account`——此前按后者调用会 404，注销按钮点了没反应。
 */
export function deleteAccount(password: string): Promise<null> {
  return post<null>('/auth/account/deactivate', { password })
}
