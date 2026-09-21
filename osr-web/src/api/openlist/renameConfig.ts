import request from '@/api/request'

export interface CategoryRule {
  id?: number
  mediaType: string
  seq?: number
  genreIds?: string
  originalLanguages?: string
  originCountries?: string
  targetDir: string
  isFallback: string
}

/** 模板里的一个可用变量；清单由后端从 MediaInfo 的渲染上下文生成 */
export interface TemplateVariable {
  name: string
  /** 中文说明，新字段尚未登记时为 null */
  label: string | null
  /** 剧集样例里的取值 */
  sample: string
  /** 常用变量先展示，其余收在「更多」里 */
  common: boolean
}

/** 模板预览：电影样例与剧集样例各渲染一份 */
export interface TemplatePreview {
  movie: string
  tv: string
}

export function getRenameTemplateApi() {
  return request.get<any, { template: string; defaultTemplate: string; variables?: TemplateVariable[] }>('/openliststrm/rename-config/template')
}

export function previewRenameTemplateApi(template: string) {
  return request.post<any, TemplatePreview>('/openliststrm/rename-config/template/preview', { template })
}

export function updateRenameTemplateApi(template: string) {
  return request.put('/openliststrm/rename-config/template', { template })
}

export function getCategoryRulesApi(mediaType: string) {
  return request.get<any, CategoryRule[]>('/openliststrm/rename-category-rules', { params: { mediaType } })
}

export function saveCategoryRulesApi(mediaType: string, rules: CategoryRule[]) {
  return request.put(`/openliststrm/rename-category-rules/${mediaType}`, rules)
}
