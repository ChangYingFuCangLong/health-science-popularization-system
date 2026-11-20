import type { LoginResult, UserInfo } from '@/api/types'
import { get, post, put } from '@/api/request'

export interface RegisterPayload {
  username: string
  password: string
  nickname?: string
}

export function register(payload: RegisterPayload): Promise<null> {
  return post<null>('/auth/register', payload)
}

export function login(username: string, password: string): Promise<LoginResult> {
  return post<LoginResult>('/auth/login', { username, password })
}

export function logout(): Promise<null> {
  return post<null>('/auth/logout')
}

export function fetchMe(): Promise<UserInfo> {
  return get<UserInfo>('/auth/me')
}

export interface UpdateProfilePayload {
  nickname?: string
  email?: string
  avatar?: string
}

export function updateProfile(payload: UpdateProfilePayload): Promise<null> {
  return put<null>('/user/profile', payload)
}

export function updatePassword(oldPassword: string, newPassword: string): Promise<null> {
  return put<null>('/user/password', { oldPassword, newPassword })
}
