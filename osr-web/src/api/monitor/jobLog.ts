import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getJobLogListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/monitor/jobLog/list', { params })
}

export function getJobLogDetailApi(jobLogId: number) {
  return request.get<any, any>(`/monitor/jobLog/${jobLogId}`)
}
