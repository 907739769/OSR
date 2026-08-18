import request from '@/api/request'

/** 体检结果里的一集，字段与后端 EpisodeHealthItem 一一对应 */
export interface EpisodeHealthItem {
  episode: number
  state: string
  /** yyyy-MM-dd；无播出日期时为 null */
  airDate: string | null
  /** 已播出天数；无播出日期时为 null 而不是 0（0 表示今天刚播） */
  overdueDays: number | null
  bucket: string
  diagnosis: string
}

/** 体检结果里的一条订阅，字段与后端 SubscriptionHealthItem 一一对应 */
export interface SubscriptionHealthItem {
  subId: number
  tmdbId: string
  title: string
  posterPath: string | null
  mediaType: string
  season: number
  autoSearch: boolean
  lastSearchTime: string | null
  missStreak: number
  rejectDetail: string | null
  maxOverdueDays: number | null
  diagnoses: string[]
  buckets: string[]
  episodes: EpisodeHealthItem[]
}

/** 一次体检的完整结果 */
export interface EpisodeHealthReport {
  overdueDays: number
  subscriptionCount: number
  episodeCount: number
  bucketCounts: Record<string, number>
  diagnosisCounts: Record<string, number>
  subscriptions: SubscriptionHealthItem[]
}

/** 拉一次体检报告。无分页——这个页面的价值在于一眼看完全部问题 */
export function getPtHealthApi() {
  return request.get<any, EpisodeHealthReport>('/openliststrm/pt-health')
}

/** 批量开启自动补搜，返回实际生效的条数（无权操作的订阅会被后端过滤掉） */
export function enableAutoSearchApi(ids: number[]) {
  return request.post<any, number>('/openliststrm/pt-health/enable-auto-search', null, {
    params: { ids: ids.join(',') }
  })
}

/**
 * 对一条订阅立刻补搜它当前所有缺集，返回一句人话结果。
 * 落空时后端走 Result.error，由 request 拦截器统一弹出真实原因（候选被过滤 / 压根没搜到），
 * 业务层 catch 里不要再补一句通用的「补搜失败」把它盖掉。
 *
 * **必须覆盖默认超时**：request.ts 的默认值是 15 秒，而这个接口是同步跑完整轮检索才返回的，
 * 后端自己给的量级是「每个索引器 30 秒软上限、索引器之间并发」——按默认值它会稳定地在
 * 15 秒被前端掐断，而后端还在跑、多半还真的推送成功了。用户看到的是一句网络错误，
 * 于是再点一次，索引器被打两遍。订阅页的 searchSupplementApi 早就为此写了 60 秒，
 * 这里当初漏了。
 */
export function searchMissingApi(subId: number) {
  return request.post<any, string>(`/openliststrm/pt-health/${subId}/search-missing`, null, {
    timeout: 60000
  })
}
