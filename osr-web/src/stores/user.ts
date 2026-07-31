import { defineStore } from 'pinia'
import { ref } from 'vue'
import Cookies from 'js-cookie'
import type { UserInfo, LoginRequest, LoginResponse } from '@/types'
import { loginApi, logoutApi, getUserInfoApi, getRoutersApi, refreshApi } from '@/api/auth'

export interface MenuRoute {
  path: string
  name?: string
  component?: string
  hidden?: boolean
  redirect?: string
  meta?: {
    title?: string
    icon?: string
    alwaysShow?: boolean
  }
  children?: MenuRoute[]
}

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(Cookies.get('token') || '')
  const refreshToken = ref<string>(Cookies.get('refreshToken') || '')
  const userInfo = ref<UserInfo | null>(null)
  const roles = ref<string[]>([])
  const permissions = ref<string[]>([])
  const routes = ref<MenuRoute[]>([])

  // cookie 仅作存储介质，有效期跟随 refresh token；access token 自身的 exp 才是安全边界。
  // 若 cookie 与 access token 同时过期，路由守卫会在任何请求发出前就跳登录页，刷新逻辑将永远不被触发。
  const setToken = (newToken: string, refreshExpireTime?: number) => {
    token.value = newToken
    const cookieOpts = refreshExpireTime
      ? { expires: new Date(refreshExpireTime) }
      : { expires: 7 }
    Cookies.set('token', newToken, cookieOpts)
  }

  const setRefreshToken = (newRefreshToken: string, refreshExpireTime?: number) => {
    refreshToken.value = newRefreshToken
    const cookieOpts = refreshExpireTime
      ? { expires: new Date(refreshExpireTime) }
      : { expires: 7 }
    Cookies.set('refreshToken', newRefreshToken, cookieOpts)
  }

  const clearToken = () => {
    token.value = ''
    refreshToken.value = ''
    userInfo.value = null
    roles.value = []
    permissions.value = []
    routes.value = []
    Cookies.remove('token')
    Cookies.remove('refreshToken')
  }

  const refreshTokenFn = async () => {
    const rt = refreshToken.value || Cookies.get('refreshToken') || ''
    if (!rt) {
      throw new Error('No refresh token')
    }
    const data = await refreshApi(rt) as LoginResponse
    setToken(data.token, data.refreshExpireTime)
    setRefreshToken(data.refreshToken, data.refreshExpireTime)
    return data
  }

  const login = async (loginForm: LoginRequest) => {
    const data = await loginApi(loginForm) as LoginResponse
    setToken(data.token, data.refreshExpireTime)
    setRefreshToken(data.refreshToken, data.refreshExpireTime)
    userInfo.value = {
      userId: data.userId,
      loginName: data.loginName,
      userName: data.userName,
      roles: [],
      permissions: []
    }
    return data
  }

  const logout = async () => {
    try {
      await logoutApi()
    } finally {
      clearToken()
    }
  }

  const getUserInfo = async () => {
    const data = await getUserInfoApi()
    userInfo.value = {
      userId: (data.user as any).userId,
      loginName: (data.user as any).loginName,
      userName: (data.user as any).userName,
      roles: data.roles,
      permissions: data.permissions
    }
    roles.value = data.roles
    permissions.value = data.permissions
    return userInfo.value
  }

  const getRouters = async () => {
    const data = await getRoutersApi()
    routes.value = data as unknown as MenuRoute[]
    return routes.value
  }

  return { token, refreshToken, userInfo, roles, permissions, routes, setToken, setRefreshToken, clearToken, login, logout, getUserInfo, getRouters, refreshTokenFn }
})
