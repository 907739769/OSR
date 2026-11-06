import request from '@/api/request'
export type JobKind = 'SUBSCRIPTION_SEARCH' | 'DOWNLOAD_RETRY'
export interface JobItem { targetId: number; label: string; state: string; message: string }
export interface BackgroundJob {
  id: string; kind: JobKind; state: string; cancelRequested: boolean
  createTime: string | null; updateTime: string | null; items: JobItem[]
}
export const listBackgroundJobsApi = () => request.get<any, BackgroundJob[]>('/openliststrm/background-jobs', { silent: true })
export const submitBackgroundJobApi = (kind: JobKind, ids: number[]) => request.post<any, BackgroundJob>('/openliststrm/background-jobs', { kind, ids })
export const cancelBackgroundJobApi = (id: string) => request.post<any, BackgroundJob>(`/openliststrm/background-jobs/${id}/cancel`)
export const retryBackgroundJobApi = (id: string) => request.post<any, BackgroundJob>(`/openliststrm/background-jobs/${id}/retry`)
