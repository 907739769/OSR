import { describe, it, expect } from 'vitest'
import { nextTick, ref } from 'vue'
import {
  UNKNOWN_VALUE,
  activeFilterCount,
  buildCandidateFacets,
  candidateTarget,
  emptyCandidateFilter,
  filterCandidates,
  useCandidateFilter
} from '../useCandidateFilter'

const cand = (over: Record<string, any>) => ({
  title: 'Some.Show.S01E01.1080p.WEB-DL',
  indexerId: 1,
  indexerName: 'A站',
  resolution: '1080p',
  source: 'WEB-DL',
  seeders: 5,
  free: false,
  parsedEpisode: 1,
  parsedEpisodeEnd: null,
  ...over
})

const list = [
  cand({ title: 'Some.Show.S01E01.2160p.HDR.WEB-DL', resolution: '2160p' }),
  cand({ title: 'Some.Show.S01E01-E04.1080p.BluRay', indexerId: 2, indexerName: 'B站', source: 'BluRay', parsedEpisodeEnd: 4, free: true }),
  cand({ title: 'Some.Show.S01.720p', indexerId: 3, indexerName: 'C站', resolution: '720p', parsedEpisode: null, seeders: 0 }),
  cand({ title: 'Some.Show.S01E01.Unknown', resolution: null, source: '' })
]

describe('candidateTarget', () => {
  it('与两端「目标」chip 的判据一致：区间 > 单集 > 整季', () => {
    expect(candidateTarget(cand({ parsedEpisode: 1, parsedEpisodeEnd: 4 }))).toBe('RANGE')
    // 结尾等于起点的「区间」按单集显示，筛选也必须归到单集
    expect(candidateTarget(cand({ parsedEpisode: 3, parsedEpisodeEnd: 3 }))).toBe('EPISODE')
    expect(candidateTarget(cand({ parsedEpisode: null }))).toBe('SEASON')
  })
})

describe('filterCandidates', () => {
  it('空条件原样返回全部候选', () => {
    expect(filterCandidates(list, emptyCandidateFilter())).toHaveLength(4)
  })

  it('关键词空格分隔全部命中、忽略大小写，- 开头排除', () => {
    const f = { ...emptyCandidateFilter(), keyword: 'show s01e01 -hdr' }
    expect(filterCandidates(list, f).map(c => c.title)).toEqual([
      'Some.Show.S01E01-E04.1080p.BluRay',
      'Some.Show.S01E01.Unknown'
    ])
  })

  it('单独一个 - 不会把全部候选排除掉', () => {
    expect(filterCandidates(list, { ...emptyCandidateFilter(), keyword: ' - ' })).toHaveLength(4)
  })

  it('按站点 id、目标、分辨率、片源多选过滤，维度之间取交集', () => {
    expect(filterCandidates(list, { ...emptyCandidateFilter(), indexerIds: [2, 3] })).toHaveLength(2)
    expect(filterCandidates(list, { ...emptyCandidateFilter(), targets: ['SEASON'] })[0].indexerId).toBe(3)
    expect(filterCandidates(list, { ...emptyCandidateFilter(), resolutions: ['2160p', '720p'] })).toHaveLength(2)
    expect(filterCandidates(list, {
      ...emptyCandidateFilter(), indexerIds: [1], resolutions: ['1080p']
    })).toHaveLength(0)
  })

  it('没解析出分辨率/片源的候选能被「未识别」选中，勾选具体值时被滤掉', () => {
    expect(filterCandidates(list, { ...emptyCandidateFilter(), resolutions: [UNKNOWN_VALUE] }).map(c => c.title))
      .toEqual(['Some.Show.S01E01.Unknown'])
    expect(filterCandidates(list, { ...emptyCandidateFilter(), sources: [UNKNOWN_VALUE] })).toHaveLength(1)
  })

  it('仅免费、仅有做种', () => {
    expect(filterCandidates(list, { ...emptyCandidateFilter(), freeOnly: true })).toHaveLength(1)
    expect(filterCandidates(list, { ...emptyCandidateFilter(), seededOnly: true })).toHaveLength(3)
  })
})

describe('buildCandidateFacets', () => {
  it('只列真实出现过的取值并带计数；分辨率从高到低、未识别排最后', () => {
    const facets = buildCandidateFacets(list)
    expect(facets.indexers.map(o => o.title)).toEqual(['A站 (2)', 'B站 (1)', 'C站 (1)'])
    expect(facets.targets.map(o => o.value)).toEqual(['EPISODE', 'RANGE', 'SEASON'])
    expect(facets.resolutions.map(o => o.value)).toEqual(['2160p', '1080p', '720p', UNKNOWN_VALUE])
    expect(facets.resolutions[facets.resolutions.length - 1].title).toBe('未识别 (1)')
  })
})

describe('useCandidateFilter', () => {
  it('候选列表换了（新搜一次）就清空筛选条件', async () => {
    const candidates = ref<any[]>(list)
    const { filter, filteredCandidates, filterCount } = useCandidateFilter(candidates)
    filter.indexerIds = [3]
    filter.keyword = 'show'
    expect(filteredCandidates.value).toHaveLength(1)
    expect(filterCount.value).toBe(2)

    candidates.value = [cand({ indexerId: 9, indexerName: 'Z站' })]
    await nextTick()

    expect(activeFilterCount(filter)).toBe(0)
    expect(filteredCandidates.value).toHaveLength(1)
  })
})
