import { computed, reactive, watch, type Ref } from 'vue'

/**
 * 手动搜索候选列表的本地筛选。
 *
 * PC 表格与移动端卡片共用这一份：筛选判据两端各写一份的话，同一批候选在两端筛出不同结果，
 * 而这件事既不报错也不留日志。筛选纯在前端做——候选已经整批拿回来了（后端没有分页），
 * 为了筛一下再打一轮索引器请求是几十秒的等待，完全不值当。
 */

/** 候选覆盖范围：单集 / 区间包 / 整季（含解析不出集号的，与表格「目标」列的三种 chip 一一对应） */
export type CandidateTarget = 'EPISODE' | 'RANGE' | 'SEASON'

/** 分辨率/片源没解析出来的候选在筛选里的取值。单独成一档而不是直接丢掉：否则勾选任意分辨率都会把它们一起滤掉，用户却看不出少了哪批 */
export const UNKNOWN_VALUE = '__unknown__'

export interface CandidateFilterState {
  /** 标题关键词，空格分隔、全部命中才保留；`-` 开头表示排除（如 `-HDR`） */
  keyword: string
  indexerIds: number[]
  targets: CandidateTarget[]
  resolutions: string[]
  sources: string[]
  freeOnly: boolean
  seededOnly: boolean
}

export interface FacetOption<T> {
  value: T
  title: string
  count: number
}

export interface CandidateFacets {
  indexers: FacetOption<number>[]
  targets: FacetOption<CandidateTarget>[]
  resolutions: FacetOption<string>[]
  sources: FacetOption<string>[]
}

const TARGET_LABELS: Record<CandidateTarget, string> = {
  EPISODE: '单集',
  RANGE: '区间包',
  SEASON: '整季'
}

/** 判据必须与两端「目标」chip 的 v-if 链逐字一致，否则筛「单集」会筛出一个显示成区间的候选 */
export const candidateTarget = (c: any): CandidateTarget => {
  if (c.parsedEpisode && c.parsedEpisodeEnd > c.parsedEpisode) return 'RANGE'
  if (c.parsedEpisode) return 'EPISODE'
  return 'SEASON'
}

const orUnknown = (v: unknown) => (v === null || v === undefined || v === '' ? UNKNOWN_VALUE : String(v))

export const emptyCandidateFilter = (): CandidateFilterState => ({
  keyword: '',
  indexerIds: [],
  targets: [],
  resolutions: [],
  sources: [],
  freeOnly: false,
  seededOnly: false
})

/** 当前生效的筛选条件个数，关键词算一个 */
export const activeFilterCount = (f: CandidateFilterState): number =>
  (f.keyword.trim() ? 1 : 0)
  + (f.indexerIds.length ? 1 : 0)
  + (f.targets.length ? 1 : 0)
  + (f.resolutions.length ? 1 : 0)
  + (f.sources.length ? 1 : 0)
  + (f.freeOnly ? 1 : 0)
  + (f.seededOnly ? 1 : 0)

export const filterCandidates = (list: any[], f: CandidateFilterState): any[] => {
  const tokens = f.keyword.trim().toLowerCase().split(/\s+/).filter(Boolean)
  // 单独一个 `-` 不当成排除空串（那会把全部候选排掉），直接忽略
  const include = tokens.filter(t => !t.startsWith('-'))
  const exclude = tokens.filter(t => t.startsWith('-') && t.length > 1).map(t => t.slice(1))
  return list.filter((c) => {
    const title = String(c.title ?? '').toLowerCase()
    if (include.some(t => !title.includes(t))) return false
    if (exclude.some(t => title.includes(t))) return false
    if (f.indexerIds.length && !f.indexerIds.includes(c.indexerId)) return false
    if (f.targets.length && !f.targets.includes(candidateTarget(c))) return false
    if (f.resolutions.length && !f.resolutions.includes(orUnknown(c.resolution))) return false
    if (f.sources.length && !f.sources.includes(orUnknown(c.source))) return false
    if (f.freeOnly && !c.free) return false
    if (f.seededOnly && !(c.seeders > 0)) return false
    return true
  })
}

/** 分辨率按数值从高到低（2160p 在 1080p 前面），解析不出数字的排在后面 */
const resolutionRank = (v: string) => {
  const n = parseInt(v, 10)
  return Number.isNaN(n) ? -1 : n
}

/**
 * 从候选本身归纳出筛选项，每项带着计数。只列这批候选里真实出现过的值——
 * 给一个固定的分辨率清单的话，勾上一个压根不存在的值只会得到一张空表。
 * 计数基于全量候选而不是当前筛选结果：随筛选联动的计数会让选项在勾选过程中跳来跳去。
 */
export const buildCandidateFacets = (list: any[]): CandidateFacets => {
  const indexers = new Map<number, { name: string; count: number }>()
  const targets = new Map<CandidateTarget, number>()
  const resolutions = new Map<string, number>()
  const sources = new Map<string, number>()
  for (const c of list) {
    const idx = indexers.get(c.indexerId) ?? { name: c.indexerName || `#${c.indexerId}`, count: 0 }
    idx.count++
    indexers.set(c.indexerId, idx)
    const t = candidateTarget(c)
    targets.set(t, (targets.get(t) ?? 0) + 1)
    const r = orUnknown(c.resolution)
    resolutions.set(r, (resolutions.get(r) ?? 0) + 1)
    const s = orUnknown(c.source)
    sources.set(s, (sources.get(s) ?? 0) + 1)
  }
  const label = (v: string) => (v === UNKNOWN_VALUE ? '未识别' : v)
  const unknownLast = (a: string, b: string) => Number(a === UNKNOWN_VALUE) - Number(b === UNKNOWN_VALUE)
  return {
    indexers: [...indexers.entries()]
      .sort((a, b) => a[1].name.localeCompare(b[1].name))
      .map(([value, { name, count }]) => ({ value, title: `${name} (${count})`, count })),
    targets: (Object.keys(TARGET_LABELS) as CandidateTarget[])
      .filter(t => targets.has(t))
      .map(t => ({ value: t, title: `${TARGET_LABELS[t]} (${targets.get(t)})`, count: targets.get(t)! })),
    resolutions: [...resolutions.entries()]
      .sort((a, b) => unknownLast(a[0], b[0]) || resolutionRank(b[0]) - resolutionRank(a[0]) || a[0].localeCompare(b[0]))
      .map(([value, count]) => ({ value, title: `${label(value)} (${count})`, count })),
    sources: [...sources.entries()]
      .sort((a, b) => unknownLast(a[0], b[0]) || b[1] - a[1] || a[0].localeCompare(b[0]))
      .map(([value, count]) => ({ value, title: `${label(value)} (${count})`, count }))
  }
}

/**
 * 候选弹窗用的筛选状态。候选列表一换（新搜了一次）就清空条件：
 * 上一批的站点/分辨率取值在新列表里未必存在，留着会得到一张莫名其妙的空表。
 */
export function useCandidateFilter(candidates: Ref<any[]>) {
  const filter = reactive<CandidateFilterState>(emptyCandidateFilter())
  const resetFilter = () => Object.assign(filter, emptyCandidateFilter())
  watch(candidates, resetFilter)

  const facets = computed(() => buildCandidateFacets(candidates.value))
  const filteredCandidates = computed(() => filterCandidates(candidates.value, filter))
  const filterCount = computed(() => activeFilterCount(filter))

  return { filter, facets, filteredCandidates, filterCount, resetFilter }
}
