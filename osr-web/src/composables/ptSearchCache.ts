import type { ResourceSearchResult } from '@/api/openlist/ptSearch'

/**
 * 资源搜索页「最近一次搜索」的本地持久化。只存一份，按用户分键。
 *
 * 为什么放前端、放 localStorage：
 * - 页面不在 keep-alive 里，离开再回来（最常见的是「转为订阅」跳走再返回）状态就没了；
 *   内存那一层由 `usePtResourceSearch` 的模块级状态负责，这里管的是刷新、以及手机上 PWA 被系统杀掉之后。
 *   只存内存的话手机上几乎等于没做——切个应用回来就是一张空页。
 * - 不放后端：「已订阅」标记按用户不同、重启即丢、而且用户主动重搜同一个关键词时要的就是新结果。
 *
 * 读写一律吞异常（隐私模式会抛、几百条带长简介的结果可能超配额），记不住就只留在内存里，不影响当次使用。
 * 键带用户 ID：同一个浏览器换账号不串；退出登录时 `clearCachedSearches` 整批清掉。
 */

const PREFIX = 'osr-pt-search:'
/** 结构变了就升版本，旧数据直接作废，不做迁移——它只是一份缓存 */
const VERSION = 1

/**
 * 超过这个时长不再恢复。站上的「免费」与做种数几小时就会变，太旧的结果拿去直接下载，
 * 可能推下去一个已经不免费的种子；页面上另有「多久以前」的说明，这里只是兜底上限。
 */
export const CACHE_TTL_MS = 24 * 60 * 60 * 1000

export interface CachedSearch {
  /** 产出这批结果的关键词（不是输入框里的当前值，后者可能已经改过） */
  keyword: string
  /** 产出这批结果时选的站点；空表示全部 */
  indexerIds: number[]
  /** 搜索完成的时刻（毫秒） */
  searchedAt: number
  result: ResourceSearchResult
}

interface Stored extends CachedSearch {
  version: number
}

export const cacheKey = (userId: number | string) => `${PREFIX}${userId}`

export function loadCachedSearch(userId: number | string | null | undefined, now: number = Date.now()): CachedSearch | null {
  if (userId == null) return null
  try {
    const raw = localStorage.getItem(cacheKey(userId))
    if (!raw) return null
    const data = JSON.parse(raw) as Stored
    if (data?.version !== VERSION || !data.result || !Array.isArray(data.result.items)) return null
    if (typeof data.searchedAt !== 'number' || now - data.searchedAt > CACHE_TTL_MS) {
      localStorage.removeItem(cacheKey(userId))
      return null
    }
    return {
      keyword: String(data.keyword ?? ''),
      indexerIds: Array.isArray(data.indexerIds) ? data.indexerIds : [],
      searchedAt: data.searchedAt,
      result: data.result
    }
  } catch {
    return null
  }
}

/** @return 是否写进去了；写不进去时调用方照常用内存里那份 */
export function saveCachedSearch(userId: number | string | null | undefined, entry: CachedSearch): boolean {
  if (userId == null) return false
  try {
    localStorage.setItem(cacheKey(userId), JSON.stringify({ version: VERSION, ...entry } satisfies Stored))
    return true
  } catch {
    // 配额满了的话留着旧的那份更糟：下次恢复出来的是上上次的结果
    try {
      localStorage.removeItem(cacheKey(userId))
    } catch {
      // 读写都不行就算了
    }
    return false
  }
}

/** 退出登录时调用：搜过的关键词在多用户部署里算个人信息，不留给下一个在这台浏览器上登录的人 */
export function clearCachedSearches() {
  try {
    const keys: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k?.startsWith(PREFIX)) keys.push(k)
    }
    keys.forEach(k => localStorage.removeItem(k))
  } catch {
    // 隐私模式下 localStorage 会抛，本来也没写进去
  }
}
