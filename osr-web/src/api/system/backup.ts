import request from '@/api/request'

/** 一个分区的恢复结果：预览时是「将会」，恢复后是「已经」 */
export interface BackupSectionResult {
  key: string
  label: string
  /** 备份里的条数 */
  total: number
  inserted: number
  updated: number
  unchanged: number
  skipped: number
  /** 整表替换的分区（重命名分类规则）里被替换掉的现有条数 */
  replaced: number
  /** 订阅：在后台逐条重建，inserted 是「将新建」的条数 */
  async: boolean
  warnings: string[]
  omittedWarnings: number
}

export interface BackupPreview {
  exportedAt: string
  includeSecrets: boolean
  sections: BackupSectionResult[]
}

export interface RestoreResult {
  sections: BackupSectionResult[]
  /** 订阅已在后台开始重建，需要轮询进度 */
  subscriptionsRestoring: boolean
}

export interface SubscriptionRestoreStatus {
  running: boolean
  total: number
  processed: number
  created: number
  failed: number
  errors: string[]
  startTime: string
  finishTime: string | null
}

/** 导出、预览、恢复都要把全部配置过一遍，比默认的 15 秒宽松些 */
const LONG_TIMEOUT = 120000

/** 返回备份文件全文 */
export function exportBackupApi(includeSecrets: boolean) {
  return request.get<any, string>('/openliststrm/backup/export', {
    params: { includeSecrets },
    timeout: LONG_TIMEOUT
  })
}

/** 以文件上传：JSON 请求体会被访问日志截取前 1000 字，而备份开头就是各种 Token */
function fileForm(file: File) {
  const form = new FormData()
  form.append('file', file)
  return form
}

export function previewBackupApi(file: File) {
  return request.post<any, BackupPreview>('/openliststrm/backup/preview', fileForm(file), {
    timeout: LONG_TIMEOUT
  })
}

export function restoreBackupApi(file: File, sections: string[]) {
  return request.post<any, RestoreResult>('/openliststrm/backup/restore', fileForm(file), {
    params: { sections: sections.join(',') },
    timeout: LONG_TIMEOUT
  })
}

export function getRestoreStatusApi() {
  return request.get<any, SubscriptionRestoreStatus | null>('/openliststrm/backup/restore/status')
}
