import request from '@/api/request'

/** PT 全局过滤与排序配置 */
export interface PtFilterConfig {
  id?: number
  minSeeders?: number
  minSize?: number
  maxSize?: number
  /** 是否仅下载免费种 0-否 1-是 */
  freeOnly?: string
  includeKeywords?: string
  excludeKeywords?: string
  /** 分辨率优先级，逗号分隔，只影响排序 */
  resolutionPriority?: string
  /** 分辨率白名单，逗号分隔，硬性过滤；空表示不限 */
  resolutionWhitelist?: string
  /** 排序维度顺序，逗号分隔 */
  sortPriority?: string
  preferredSize?: number
  /** 外语电影是否需要中文字幕 0-否 1-是 */
  requireChineseSubtitle?: string
}

export function getPtFilterConfigApi() {
  return request.get<any, PtFilterConfig>('/openliststrm/pt-filter-config')
}

export function updatePtFilterConfigApi(data: PtFilterConfig) {
  return request.put('/openliststrm/pt-filter-config', data)
}

/** 可选的排序维度清单 */
export function getSortDimensionsApi() {
  return request.get<any, string[]>('/openliststrm/pt-filter-config/sort-dimensions')
}
