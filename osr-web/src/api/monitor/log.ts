import request from '@/api/request'

export interface LogSearchParams {
  /** 日志源：all / error / access */
  type: string
  keyword?: string
  regex?: boolean
  /** 逗号分隔的级别，缺省不过滤 */
  levels?: string
  /** yyyy-MM-ddTHH:mm（datetime-local 的原样值），后端规整 */
  from?: string
  to?: string
  limit?: number
}

export interface LogSearchResult {
  lines: Record<string, unknown>[]
  truncated: boolean
  truncatedReason?: string
  scannedFiles: number
  scannedBytes: number
  elapsedMs: number
}

/** 在日志文件（含已滚动的分片）里检索。后端单次最多跑 15 秒，超时放宽到 60 秒留出网络余量 */
export function searchLogApi(params: LogSearchParams) {
  return request.get<any, LogSearchResult>('/monitor/log/search', { params, timeout: 60000 })
}
