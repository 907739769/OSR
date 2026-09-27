import type { RecordStatusOption } from '@/components/RecordStatusBar.vue'
import { formatRelativeTime } from './relativeTime'
import type { PtDownloadLiveView, PtDownloadRecordView } from '@/api/openlist/ptDownloadRecord'
import { formatFileSize } from './useRecordList'

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
  // 用户自己在下载记录页删掉的：不是故障，用中性色
  { value: 'USER_DELETED', title: '用户删除', type: 'info' },
  { value: 'OTHER', title: '其他原因', type: 'error' }
]

/**
 * 日期区间落在哪一列。与统计仪表盘趋势图三条线的分组口径一一对应；
 * 「失败时间」只对失败记录有意义（取 FAILED 行的 update_time），选它时后端会只查失败记录
 */
export const DATE_FIELD_OPTIONS = [
  { value: 'PUSHED', title: '推送时间' },
  { value: 'COMPLETED', title: '完成时间' },
  { value: 'FAILED', title: '失败时间' }
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

/**
 * 能不能点「忽略」：只对还没着落（没被接替）、也还没忽略过的失败成立。
 * 已被接替的本来就不计入待办，忽略它没有意义
 */
export const canIgnore = (item: PtDownloadRecordView) => canRetry(item) && !item.failIgnored

// ---------- 实时速度 ----------

/** 速度：复用记录页的体积格式再加 /s，两处单位写法一致 */
export const formatSpeed = (bytesPerSecond: number) => `${formatFileSize(Math.max(0, bytesPerSecond))}/s`

/** 剩余时间：只给两级精度，「剩余 1 小时 23 分 45 秒」的秒数每轮都在跳，读不出任何东西 */
export const formatEta = (seconds?: number | null) => {
  if (!seconds || seconds <= 0) return ''
  if (seconds < 60) return '不到 1 分钟'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时${minutes % 60 ? ` ${minutes % 60} 分` : ''}`
  const days = Math.floor(hours / 24)
  return `${days} 天${hours % 24 ? ` ${hours % 24} 小时` : ''}`
}

/**
 * 卡片进度条下方那一行实时状态，没有可说的时返回空串（整行不渲染）。
 *
 * 「已推送」的多集包在下载器里本来就是暂停态（等选完文件才启动），不能照实说成「已暂停」——
 * 用户会以为是自己或别人点了暂停。所以已推送只在真的有速度时才说话；
 * 下载中则把「用户在这里暂停」「在下载器里被暂停」「在跑但没速度」三种分开说。
 */
export const liveText = (item: PtDownloadRecordView, live?: PtDownloadLiveView) => {
  if (!live?.found) return ''
  const eta = formatEta(live.etaSeconds)
  const running = `↓ ${formatSpeed(live.downloadSpeed)}${eta ? ` · 剩余 ${eta}` : ''}`
  if (item.state === 'PUSHED') return live.downloadSpeed > 0 ? running : ''
  if (item.state !== 'DOWNLOADING') return ''
  if (item.userPaused) return '已暂停'
  if (live.paused) return '已在下载器中暂停'
  return live.downloadSpeed > 0 ? running : '暂无下载速度，正在等待连接做种者'
}

// ---------- 暂停 / 继续 / 删除 ----------

/**
 * 能不能暂停：只有下载中。已推送的多集包本来就是暂停态在等选文件；
 * 已完成的暂停就是停止做种，H&R 考核的做种时长会跟着停——后端还会再按实时进度拦一次
 */
export const canPause = (item: PtDownloadRecordView) => item.state === 'DOWNLOADING' && !item.userPaused

export const canResume = (item: PtDownloadRecordView) => item.state === 'DOWNLOADING' && !!item.userPaused

/** H&R 考核中（或考核结果不明）的一律不给删，这是硬边界 */
const hrBlocksDelete = (item: PtDownloadRecordView) => !!item.hrState && item.hrState !== 'SATISFIED'

/** 种子还没下完：只有这种才允许连文件一起删（下完的文件可能正被媒体库 / STRM 用着） */
const notDownloaded = (item: PtDownloadRecordView) =>
  item.state === 'PUSHED' || item.state === 'DOWNLOADING' || (item.state === 'FAILED' && item.failReasonCode === 'ZOMBIE_TIMEOUT')

/**
 * 能不能点「删除下载」：记录的种子得还在下载器里才有东西可删。
 * 失败记录里只有「下载超时」会把种子留在下载器（其余几类要么种子本来就没了、要么 OSR 已经顺手删掉），
 * 给别的失败记录挂这个按钮，点下去只会得到一句「找不到种子」。
 */
export const canDeleteTorrent = (item: PtDownloadRecordView) =>
  !!item.downloaderId && !hrBlocksDelete(item) && (notDownloaded(item) || item.state === 'COMPLETED')

export const canDeleteFiles = notDownloaded

/** 来源站点有没有 H&R 考核（索引器已删除时拿不到，按没有处理） */
export const hasHitAndRun = (item: PtDownloadRecordView) =>
  (item.hrSeedHoursRequired ?? 0) > 0 || (item.hrRatioRequired ?? 0) > 0

/** 删除确认框的说明：三种状态删掉的东西不一样，要说清楚会发生什么 */
export const deleteTorrentMessage = (item: PtDownloadRecordView) => {
  const where = item.downloaderName ? `下载器「${item.downloaderName}」` : '下载器'
  if (item.state === 'PUSHED' || item.state === 'DOWNLOADING') {
    return `将从${where}删除这个下载。相关的集会退回缺失，之后的自动补搜或 RSS 可能会推送别的种子；这个种子本身不会再被自动选中。`
  }
  if (item.state === 'COMPLETED') {
    return `将从${where}移除这个种子任务，不再做种。已下载的文件会保留：媒体库或 STRM 可能正在使用它们。`
  }
  return `这个下载已判失败，但种子仍留在${where}里。将把它从下载器移除。`
}

/** 做种数是推送那一刻的快照，卡片上要说清楚，免得被当成实时数据 */
export const SEEDERS_HINT = '做种数是推送那一刻索引器给出的快照，不随时间更新'

/** 停在「已推送」超过这么久就提示：正常情况下下载器几分钟内就会开始下载并被追踪任务认领 */
export const STALE_PUSHED_MINUTES = 60

/**
 * 「已推送」迟迟不开始的提示文案，不需要提示时返回空串。
 * 已推送是稳态标签（StatusChip 不带 pulse），推送后十分钟和推送后十小时在卡片上长得一模一样，
 * 而后者多半是下载器没接住（任务被手动删了、做种数为 0 拿不到元数据）——用户最该去看的正是它。
 */
export const stalePushedHint = (item: PtDownloadRecordView, now: number = Date.now()) => {
  if (item.state !== 'PUSHED' || !item.pushedTime) return ''
  // Safari 不认 `yyyy-MM-dd HH:mm:ss` 里的空格，同 relativeTime.ts
  const t = new Date(item.pushedTime.replace(' ', 'T')).getTime()
  if (Number.isNaN(t) || now - t < STALE_PUSHED_MINUTES * 60000) return ''
  return `推送于 ${formatRelativeTime(item.pushedTime, now)}，下载器仍未开始下载，可到下载器确认任务状态`
}
