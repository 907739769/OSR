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
  /** 站点详情页；与 downloadUrl 是两件事，后者是 .torrent/磁力地址 */
  detailUrl?: string | null
  /** description 第一段（站点模板里的别名列表）；没有或过长（多半是剧情简介）为 null */
  subtitle?: string | null
  /** 识别出的作品 TMDb ID；识别不出为 null */
  matchedTmdbId?: string | null
  /** 识别出的作品类型，后端下发大写 TV / MOVIE（PT 侧的约定）；识别不出为 null */
  mediaType?: string | null
  matchedTitle?: string | null
  matchedYear?: string | null
  /** 按 tmdbId + 媒体类型判：用户据此知道这条是补集还是新剧 */
  subscribed?: boolean
  /** 全局过滤规则会因为什么挡掉它（短标签）；null 表示会放行 */
  ruleRejection?: string | null
  ruleRejectionDetail?: string | null
}

export interface ResourceSearchResult {
  candidateCount: number
  rejectedCount: number
  tmdbLookupEnabled: boolean
  /** 按「标题 + 类型」归并出的标题组数（同一部剧的 12 集算 1 组） */
  distinctWorks: number
  /** 其中识别出作品的组数，与 distinctWorks 同一口径 */
  identifiedWorks: number
  /**
   * 下面三种「没识别全」都必须显式说出来：用户看到一片空白会读成「这些种子不是任何作品」，而真相是没识别。
   * skipped：标题组数超过上限，只识别了种子最多的那些组；truncated：预算内没跑完；unavailable：TMDb key 未配置
   */
  tmdbLookupSkipped: boolean
  tmdbLookupTruncated: boolean
  tmdbLookupUnavailable: boolean
  items: ResourceItem[]
}

/**
 * 按关键词搜全部（或所选）站点。超时按两段预算配：单个站点 30 秒检索（`pt.search.indexer-budget-ms`）
 * + 作品识别 30 秒（`pt.search.tmdb-lookup-budget-ms`），两者都是软上限，这里要盖住两段之和再留余量。
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
