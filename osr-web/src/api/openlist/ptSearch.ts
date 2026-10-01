import request from '@/api/request'

/** 资源搜索的一条结果：字段与订阅内候选列表同一份，多出规则标注两列 */
export interface ResourceItem {
  title: string
  size: number
  seeders: number
  peers: number
  free: boolean
  downloadVolumeFactor: number
  resolution?: string
  source?: string
  indexerName: string
  indexerId: number
  guid?: string
  downloadUrl?: string
  infoHash?: string
  parsedYear?: string
  pubDate?: string
  description?: string
  files?: number
  parsedEpisode?: number
  parsedEpisodeEnd?: number
  parsedTitle?: string
  parsedSeason?: number
  hitAndRun: boolean
  /** 全局过滤规则会因为什么挡掉它（短标签）；null 表示会放行 */
  ruleRejection?: string | null
  ruleRejectionDetail?: string | null
}

export interface ResourceSearchResult {
  candidateCount: number
  rejectedCount: number
  items: ResourceItem[]
}

/**
 * 按关键词搜全部（或所选）站点。超时与订阅内手动搜索一样放宽：
 * 单个站点有 30 秒检索预算，慢站点会把整次请求拖到半分钟以上
 */
export function searchResourceApi(data: { keyword: string; indexerIds?: number[] }) {
  return request.post<any, ResourceSearchResult>('/openliststrm/pt-search/search', data, { timeout: 120000 })
}

/** 直接下载可选的下载器（仅管理员）：启用中且参与下载，只做种的不列 */
export function listResourceDownloadersApi() {
  return request.get<any, { id: number; name: string }[]>('/openliststrm/pt-search/downloaders')
}

/** 直接推给指定下载器（仅管理员），不建下载记录。成功时返回「已推送，保存到 …」 */
export function pushResourceApi(data: Partial<ResourceItem> & { downloaderId: number }) {
  return request.post<any, string>('/openliststrm/pt-search/push', data)
}
