import axios from 'axios'

export interface HitokotoResult {
  hitokoto: string
  from: string
}

/**
 * 一言 (hitokoto.cn) 是第三方接口，独立于后端 API，
 * 不能复用 @/api/request 里绑定了后端 baseURL 和 token 拦截逻辑的 axios 实例
 */
export function getHitokotoApi() {
  return axios.get<HitokotoResult>('https://v1.hitokoto.cn/', { timeout: 5000 }).then((res) => res.data)
}
