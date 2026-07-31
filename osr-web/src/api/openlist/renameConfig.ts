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

export function getRenameTemplateApi() {
  return request.get<any, { template: string }>('/openliststrm/rename-config/template')
}

export function previewRenameTemplateApi(template: string) {
  return request.post<any, string>('/openliststrm/rename-config/template/preview', { template })
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
