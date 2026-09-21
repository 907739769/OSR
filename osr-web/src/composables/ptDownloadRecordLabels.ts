import type { RecordStatusOption } from '@/components/RecordStatusBar.vue'
import type { PtDownloadRecordView } from '@/api/openlist/ptDownloadRecord'

/**
 * PT 下载记录的状态 / 失败原因 / H&R 标签，PC 与移动端共用这一份。
 *
 * 原先两端各在 index.vue 里写了一套 switch，新增一种失败原因分类（`METADATA_TIMEOUT`）
 * 就得改两处，漏一处的表现是那一端显示成「其他原因」——不报错，只是悄悄说错。
 * 这里的选项数组同时喂给状态下拉、统计条与 StatusChip，不要在模板里再写一份。
 */

type ChipType = RecordStatusOption['type']

export const DOWNLOAD_STATE_OPTIONS: RecordStatusOption[] = [
  { value: 'PUSHED', title: '已推送', type: 'info' },
  { value: 'DOWNLOADING', title: '下载中', type: 'warning' },
  { value: 'COMPLETED', title: '已完成', type: 'success' },
  { value: 'FAILED', title: '失败', type: 'error' }
]

/**
 * 失败原因分类。「下载超时 / 无目标集 / 种子无响应」都不是错误：占位集已退回、会继续搜索，
 * 用 warning 而不是 error，免得一页红色吓人。
 */
export const FAIL_REASON_OPTIONS: RecordStatusOption[] = [
  { value: 'TORRENT_NOT_FOUND', title: '种子丢失', type: 'error' },
  { value: 'ZOMBIE_TIMEOUT', title: '下载超时', type: 'warning' },
  { value: 'NO_TARGET_EPISODE', title: '无目标集', type: 'warning' },
  { value: 'METADATA_TIMEOUT', title: '种子无响应', type: 'warning' },
  { value: 'OTHER', title: '其他原因', type: 'error' }
]

export const HR_STATE_OPTIONS: RecordStatusOption[] = [
  { value: 'PENDING', title: '保种中', type: 'warning' },
  { value: 'SATISFIED', title: '已达标', type: 'success' },
  { value: 'VIOLATED', title: '可能已 H&R', type: 'error' }
]

const lookup = (options: RecordStatusOption[]) => {
  const byValue = Object.fromEntries(options.map(o => [o.value, o]))
  return (value?: string | null) => (value ? byValue[value] : undefined)
}

const stateOf = lookup(DOWNLOAD_STATE_OPTIONS)
const failReasonOf = lookup(FAIL_REASON_OPTIONS)
const hrStateOf = lookup(HR_STATE_OPTIONS)

export const stateLabel = (state?: string | null) => stateOf(state)?.title ?? state ?? '-'
export const stateTagType = (state?: string | null): ChipType => stateOf(state)?.type ?? 'info'

// 认不出的分类（后端新增了而前端还没跟上）按「其他原因」显示，与原先的 default 分支一致
export const failReasonCodeLabel = (code?: string | null) => failReasonOf(code)?.title ?? '其他原因'
export const failReasonTagType = (code?: string | null): ChipType => failReasonOf(code)?.type ?? 'error'

export const hrStateLabel = (state?: string | null) => hrStateOf(state)?.title ?? state ?? '-'
export const hrTagType = (state?: string | null): ChipType => hrStateOf(state)?.type ?? 'warning'

/**
 * 保种进度摘要。要求是「做满 N 小时 或 分享率达到 R」的或关系，两项都展示，
 * 让用户自己看哪一项先够；站点没配的那一项（阈值 0）不显示目标值。
 */
export const hrProgress = (item: PtDownloadRecordView) => {
  const hours = ((item.hrSeedSeconds ?? 0) / 3600).toFixed(1)
  const ratio = (item.hrRatio ?? 0).toFixed(2)
  const seedRequired = item.hrSeedHoursRequired ?? 0
  const ratioRequired = item.hrRatioRequired ?? 0
  return [
    seedRequired > 0 ? `做种 ${hours}/${seedRequired}h` : `做种 ${hours}h`,
    ratioRequired > 0 ? `分享率 ${ratio}/${ratioRequired}` : `分享率 ${ratio}`
  ].join('，')
}

/** 下载进度百分比（整数）；没有进度时为 0 */
export const progressPercent = (item: PtDownloadRecordView) => Math.round((item.progress || 0) * 100)

/** 是否展示进度条：只有下载中 / 已完成的进度有意义 */
export const hasProgress = (item: PtDownloadRecordView) =>
  item.state === 'DOWNLOADING' || item.state === 'COMPLETED'

/**
 * 能不能在这条记录上点「重试」：只有失败记录，且后面还没有接替它的推送。
 * 被接替的失败记录再重试一次多半是白跑——该去看的是接替它的那条。
 */
export const canRetry = (item: PtDownloadRecordView) => item.state === 'FAILED' && !item.supersededById

/** 做种数是推送那一刻的快照，卡片上要说清楚，免得被当成实时数据 */
export const SEEDERS_HINT = '做种数是推送那一刻索引器给出的快照，不随时间更新'
