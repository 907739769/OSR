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
  /** 逗号分隔，种子描述命中任一则淘汰；描述为空时放行 */
  descriptionExcludeKeywords?: string
  /** 分辨率优先级，逗号分隔，只影响排序 */
  resolutionPriority?: string
  /** 分辨率白名单，逗号分隔，硬性过滤；空表示不限 */
  resolutionWhitelist?: string
  /** 媒介来源白名单（REMUX/BluRay/WEBDL 等），逗号分隔，硬性过滤；空表示不限 */
  sourceWhitelist?: string
  /** 媒介来源优先级，逗号分隔，只影响排序 */
  sourcePriority?: string
  /** 必需的质量标签，逗号分隔，须全部具备；空表示不限 */
  requiredTags?: string
  /** 命中任一则淘汰的质量标签，逗号分隔 */
  excludeTags?: string
  /** 发布组优先级，逗号分隔，只影响排序 */
  releaseGroupPriority?: string
  /** 排序维度顺序，逗号分隔 */
  sortPriority?: string
  preferredSize?: number
  /** 体积上下限与偏好体积是否按每集判定 0-否 1-是。剧集种子常是区间包/季包，整包体积是单集的数倍 */
  sizePerEpisode?: string
  /** 外语电影是否需要中文字幕 0-否 1-是 */
  requireChineseSubtitle?: string
  /** 是否直接淘汰 H&R 考核站点的种子 0-否 1-是 */
  avoidHitAndRun?: string
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

/** 分辨率 / 来源 / 质量标签的可选值（后端按全等比对，前端只许从这里选） */
export interface FilterVocabulary {
  resolutions: string[]
  sources: string[]
  tags: string[]
}

export function getFilterVocabularyApi() {
  return request.get<any, FilterVocabulary>('/openliststrm/pt-filter-config/vocabulary')
}

/** 规则试算的输入：一条种子 + 待试算的（可能未保存的）规则 */
export interface FilterPreviewRequest {
  title: string
  description?: string
  /** 字节 */
  size?: number
  seeders?: number
  free?: boolean
  hitAndRun?: boolean
  /** 按外语电影判定（影响「外语电影需中字」） */
  foreignMovie?: boolean
  config?: PtFilterConfig
}

export interface FilterPreviewResult {
  title?: string
  year?: string
  season?: number
  episode?: number
  episodeEnd?: number
  episodeCount: number
  resolution?: string
  source?: string
  releaseGroup?: string
  tags: string[]
  /** 参与体积判定的体积（字节），按每集判定时已折算 */
  effectiveSize: number
  accepted: boolean
  rejectCode?: string
  rejectLabel?: string
  rejectReason?: string
}

export function previewPtFilterApi(data: FilterPreviewRequest) {
  return request.post<any, FilterPreviewResult>('/openliststrm/pt-filter-config/preview', data)
}
