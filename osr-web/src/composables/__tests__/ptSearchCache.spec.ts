import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import {
  CACHE_TTL_MS,
  cacheKey,
  clearCachedSearches,
  loadCachedSearch,
  saveCachedSearch,
  type CachedSearch
} from '@/composables/ptSearchCache'

const entry = (over: Partial<CachedSearch> = {}): CachedSearch => ({
  keyword: '三体',
  indexerIds: [1],
  searchedAt: 1_000_000,
  result: {
    candidateCount: 1, rejectedCount: 0, tmdbLookupEnabled: true, distinctWorks: 1, identifiedWorks: 1,
    tmdbLookupSkipped: false, tmdbLookupTruncated: false, tmdbLookupUnavailable: false,
    items: [{ title: 'Three.Body.S01E01', guid: 'a' } as any]
  },
  ...over
})

describe('ptSearchCache', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('写进去的原样读回来', () => {
    expect(saveCachedSearch(7, entry())).toBe(true)

    expect(loadCachedSearch(7, 1_000_000 + 1000)).toEqual(entry())
  })

  /** 键带用户 ID：同一个浏览器换账号不串 */
  it('按用户分键，读不到别人的', () => {
    saveCachedSearch(7, entry())

    expect(loadCachedSearch(8, 1_000_000)).toBeNull()
  })

  it('不知道是谁时不读不写', () => {
    expect(saveCachedSearch(null, entry())).toBe(false)
    expect(loadCachedSearch(undefined)).toBeNull()
    expect(localStorage.length).toBe(0)
  })

  /** 太旧的结果拿去直接下载，可能推下去一个已经不免费的种子 */
  it('超过有效期的不恢复，并顺手删掉', () => {
    saveCachedSearch(7, entry())

    expect(loadCachedSearch(7, 1_000_000 + CACHE_TTL_MS + 1)).toBeNull()
    expect(localStorage.getItem(cacheKey(7))).toBeNull()
  })

  it('结构对不上（旧版本、手改过）的当作没有', () => {
    localStorage.setItem(cacheKey(7), JSON.stringify({ version: 0, ...entry() }))
    expect(loadCachedSearch(7, 1_000_000)).toBeNull()

    localStorage.setItem(cacheKey(7), '{not json')
    expect(loadCachedSearch(7, 1_000_000)).toBeNull()
  })

  /** 配额满了留着旧的那份更糟：下次恢复出来的是上上次的结果 */
  it('写不进去时返回 false 并删掉旧的那份', () => {
    saveCachedSearch(7, entry({ keyword: '旧的' }))
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    expect(saveCachedSearch(7, entry({ keyword: '新的' }))).toBe(false)
    expect(localStorage.getItem(cacheKey(7))).toBeNull()
  })

  it('localStorage 整个不可用时读写都不抛', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied') })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('denied') })

    expect(loadCachedSearch(7)).toBeNull()
    expect(saveCachedSearch(7, entry())).toBe(false)
    expect(() => clearCachedSearches()).not.toThrow()
  })

  it('退出登录时清掉所有用户的，不动别的键', () => {
    saveCachedSearch(7, entry())
    saveCachedSearch(8, entry())
    localStorage.setItem('osr-theme', 'dark')

    clearCachedSearches()

    expect(localStorage.getItem(cacheKey(7))).toBeNull()
    expect(localStorage.getItem(cacheKey(8))).toBeNull()
    expect(localStorage.getItem('osr-theme')).toBe('dark')
  })
})
