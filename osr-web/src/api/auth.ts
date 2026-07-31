import axios from 'axios'
import request from '@/api/request'
import type { LoginRequest, LoginResponse, Result } from '@/types'

export function loginApi(data: LoginRequest) {
  return request.post<any, LoginResponse>('/auth/login', data)
}

export function logoutApi() {
  return request.post('/auth/logout')
}

export function getUserInfoApi() {
  return request.get<any, { user: Record<string, unknown>; roles: string[]; permissions: string[] }>('/auth/info')
}

export function getRoutersApi() {
  return request.get<any, Record<string, unknown>[]>('/auth/routers')
}

export function changePasswordApi(data: { oldPassword: string; newPassword: string }) {
  return request.post('/auth/changePassword', data)
}

/**
 * 刷新令牌。刻意使用裸 axios 实例而非共享的 request 实例：
 * 后端刷新失败时返回 HTTP 200 + body code 401，走共享拦截器会递归触发刷新流程并死锁。
 */
export function refreshApi(refreshToken: string): Promise<LoginResponse> {
  return axios
    .post<Result<LoginResponse>>(
      `${import.meta.env.VITE_APP_BASE_API || '/api'}/auth/refresh`,
      { refreshToken },
      { timeout: 15000 }
    )
    .then((response) => {
      const { code, message, data } = response.data
      if (code !== 200) {
        throw new Error(message || '刷新令牌失败')
      }
      return data as LoginResponse
    })
}
