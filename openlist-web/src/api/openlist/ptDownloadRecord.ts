import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export interface PtDownloadRecordQuery extends SearchParams {
  subId?: number
  state?: string
  title?: string
}

export function getPtDownloadRecordListApi(params: PtDownloadRecordQuery) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-download-records', { params })
}

/** 立即重试一条失败的下载记录：按订阅标题+季/集号重新发起搜索补集 */
export function retryPtDownloadRecordApi(id: number) {
  return request.post<any, { pushed: boolean; candidateCount: number }>(
    `/openliststrm/pt-download-records/${id}/retry`
  )
}

/** 批量重试选中的失败下载记录 */
export function batchRetryPtDownloadRecordApi(ids: number[]) {
  return request.post<any, { total: number; pushedCount: number; skippedCount: number }>(
    '/openliststrm/pt-download-records/batchRetry', null, { params: { ids: ids.join(',') } }
  )
}

/** 拉黑该记录对应的种子（GUID 维度），reason 可选；返回 true=新增成功，false=已在黑名单中 */
export function blacklistGuidApi(id: number, reason?: string) {
  return request.post<any, boolean>(
    `/openliststrm/pt-download-records/${id}/blacklist-guid`, reason ? { reason } : {}
  )
}

/** 拉黑该记录标题解析出的发布组，reason 可选；返回 true=新增成功，false=已在黑名单中 */
export function blacklistReleaseGroupApi(id: number, reason?: string) {
  return request.post<any, boolean>(
    `/openliststrm/pt-download-records/${id}/blacklist-release-group`, reason ? { reason } : {}
  )
}
