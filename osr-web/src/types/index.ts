export interface Result<T = any> {
  code: number
  message: string
  data: T
}

export interface PageResult<T = any> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface UserInfo {
  userId: number
  loginName: string
  userName: string
  roles: string[]
  permissions: string[]
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  userId: number
  loginName: string
  userName: string
  permissions?: Record<string, unknown>
  expireTime: number
  refreshToken: string
  refreshExpireTime: number
}

export interface PaginationParams {
  page: number
  size: number
  orderBy?: string
  sortDir?: 'asc' | 'desc'
}

export interface SearchParams {
  pageNum: number
  pageSize: number
  [key: string]: any
}
