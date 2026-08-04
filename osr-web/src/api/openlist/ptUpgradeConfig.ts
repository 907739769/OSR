import request from '@/api/request'

/** PT 洗版（质量升级）配置 */
export interface PtUpgradeConfig {
  id?: number
  /** 洗版总开关 0-否 1-是。默认关闭，开启前必须先确认目标质量 */
  enabled?: string
  /** 比较的维度顺序，逗号分隔。刻意不含做种数/体积/促销——那些不是画质 */
  qualityPriority?: string
  /** 目标分辨率（cutoff），达到即停止洗版 */
  targetResolution?: string
  /** 目标媒介来源（cutoff），逗号分隔，命中其一即满足 */
  targetSources?: string
  /** 目标质量标签（cutoff），逗号分隔，须全部具备 */
  targetTags?: string
  /** 洗版同时在途的下载数上限，独立于补缺集 */
  maxConcurrent?: number
  /** 扫描周期（小时） */
  scanIntervalHours?: number
}

export function getPtUpgradeConfigApi() {
  return request.get<any, PtUpgradeConfig>('/openliststrm/pt-upgrade-config')
}

export function updatePtUpgradeConfigApi(data: PtUpgradeConfig) {
  return request.put('/openliststrm/pt-upgrade-config', data)
}

/** 可选的洗版比较维度清单 */
export function getQualityDimensionsApi() {
  return request.get<any, string[]>('/openliststrm/pt-upgrade-config/quality-dimensions')
}
