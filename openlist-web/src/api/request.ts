import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import { message } from '@/composables/useMessage'
import type { Result, LoginResponse } from '@/types'
import Cookies from 'js-cookie'
import { useUserStore } from '@/stores/user'

const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API || '/api',
  timeout: 15000
})

/**
 * 登录接口自己返回的 401 表示「用户名或密码错误」，不是 token 过期。
 * 若把它交给刷新流程：此时用户尚未登录、没有 refreshToken，会被直接
 * clearToken + window.location.href = '/login' 硬跳转，错误提示随页面刷新一起消失，
 * 用户输错密码后只看到页面闪回登录页，得不到任何反馈。
 */
function isLoginRequest(url?: string): boolean {
  return !!url && url.includes('/auth/login')
}

let isRefreshing = false
let retryQueue: Array<{ resolve: (token: string) => void; reject: (reason: Error) => void }> = []

const subscribeRequest = (
  resolve: (token: string) => void,
  reject: (reason: Error) => void
) => {
  retryQueue.push({ resolve, reject })
}

/**
 * 共享的 token 刷新逻辑，供 success handler（body-level 401）和 error handler（HTTP 401/403）共用
 */
async function handleTokenRefresh(originalRequest: InternalAxiosRequestConfig & { _retry?: boolean }) {
  if (originalRequest._retry) {
    return Promise.reject(new Error('Already retried'))
  }
  originalRequest._retry = true

  if (isRefreshing) {
    // 刷新失败时由 reject 唤醒，避免排队请求永久挂起；跳转登录页统一由发起刷新的那一方负责
    await new Promise<void>((resolve, reject) => {
      subscribeRequest((token: string) => {
        originalRequest.headers.Authorization = `Bearer ${token}`
        resolve()
      }, reject)
    })
    return service(originalRequest)
  }

  isRefreshing = true

  try {
    const userStore = useUserStore()
    const refreshTokenValue = Cookies.get('refreshToken')

    if (!refreshTokenValue) {
      userStore.clearToken()
      window.location.href = '/login'
      return Promise.reject(new Error('No refresh token'))
    }

    const { refreshApi } = await import('@/api/auth')
    const data = await refreshApi(refreshTokenValue) as LoginResponse

    const userStore2 = useUserStore()
    userStore2.setToken(data.token, data.refreshExpireTime)
    userStore2.setRefreshToken(data.refreshToken, data.refreshExpireTime)

    retryQueue.forEach(({ resolve }) => resolve(data.token))
    retryQueue = []

    originalRequest.headers.Authorization = `Bearer ${data.token}`
    return service(originalRequest)
  } catch (refreshError) {
    // 刷新失败：唤醒排队请求让其自行 reject，否则它们会永久挂起
    const queued = retryQueue
    retryQueue = []
    const reason = refreshError instanceof Error ? refreshError : new Error('登录已过期')
    queued.forEach(({ reject }) => reject(reason))

    const userStore = useUserStore()
    userStore.clearToken()
    message.error('登录已过期，请重新登录')
    window.location.href = '/login'
    return Promise.reject(refreshError)
  } finally {
    isRefreshing = false
  }
}

service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = Cookies.get('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

service.interceptors.response.use(
  async (response: AxiosResponse<Result>) => {
    const { code, message: msg, data } = response.data

    if (code === 200) {
      return data as any
    } else if (code === 401 && !isLoginRequest(response.config.url)) {
      // body-level 401: token expired but HTTP was 200 (permitAll endpoints like /api/auth/info)
      // trigger the same refresh flow as HTTP 401
      return handleTokenRefresh(response.config as InternalAxiosRequestConfig & { _retry?: boolean })
    } else {
      message.error(msg || '请求失败')
      return Promise.reject(new Error(msg || '请求失败'))
    }
  },
  async (error) => {
    const status = error.response?.status
    // 401=JWT过期, 403=Shiro未认证(前端JWT filter静默放行后Shiro拦截), 302=重定向
    const isAuthError = status === 401 || status === 403 || status === 302

    if (isAuthError && !isLoginRequest(error.config?.url)) {
      const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean }
      return handleTokenRefresh(originalRequest)
    } else {
      message.error(error.message || '网络错误')
      return Promise.reject(error)
    }
  }
)

export default service
