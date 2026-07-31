import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getJobListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/monitor/job/list', { params })
}

export function getJobDetailApi(jobId: number) {
  return request.get<any, any>(`/monitor/job/${jobId}`)
}

export function addJobApi(data: any) {
  return request.post('/monitor/job', data)
}

export function updateJobApi(data: any) {
  return request.put('/monitor/job', data)
}

export function deleteJobApi(jobId: number) {
  return request.delete(`/monitor/job/${jobId}`)
}

export function changeJobStatusApi(jobId: number, status: string) {
  return request.put(`/monitor/job/changeStatus/${jobId}`, { status })
}

export function runJobApi(jobId: number, jobParams?: string) {
  return request.post(`/monitor/job/run/${jobId}`, { jobParams })
}
