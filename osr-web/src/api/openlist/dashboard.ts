import request from '@/api/request'

/** 出问题的下载器 / 媒体服务器；detail 只对管理员下发（报错里常带内网地址） */
export interface TodoProblem {
  name: string
  since: string | null
  detail: string | null
}

/** 首页待办的后端信号；任一字段为 null 表示那一路没取到，不是「没有问题」 */
export interface TodoSignals {
  offlineDownloaders: TodoProblem[] | null
  unhealthyMediaServers: TodoProblem[] | null
  unresolvedFailedDownloads: number | null
}

export function getTodoSignalsApi() {
  return request.get<any, TodoSignals>('/openliststrm/dashboard/todo-signals')
}

export function getDashboardStatsApi() {
  return request.get('/openliststrm/dashboard/stats')
}

export function getCopyStatsApi(range: string) {
  return request.post('/openliststrm/dashboard/copy/stats', null, { params: { range } })
}

export function getStrmStatsApi(range: string) {
  return request.post('/openliststrm/dashboard/strm/stats', null, { params: { range } })
}

export function getRenameStatsApi(range: string) {
  return request.post('/openliststrm/dashboard/renameDetail/stats', null, { params: { range } })
}

export interface DashboardTrendPoint {
  date: string
  totalCount: number
  successCount: number
  failedCount: number
}

export function getDashboardTrendApi(type: 'copy' | 'strm' | 'rename', days: number) {
  return request.get<any, DashboardTrendPoint[]>('/openliststrm/dashboard/trend', { params: { type, days } })
}
