/**
 * TMDb 条目链接：刮错了/订错了的第一反应是去 TMDb 上看一眼这到底是哪部作品。
 *
 * 全站只此一份。重命名明细、PT 订阅、缺集体检、追剧日历、选片弹窗、热门自动订阅日志
 * 六处共用——各写一份的代价不是"多几行"，而是**大小写这一条必然漂移**：
 * rename_detail.media_type 存的是小写 `tv`/`movie`，而 pt_subscription / pt_auto_add_log
 * 存的是大写 `TV`/`MOVIE`（后端两条链路各有各的约定，不打算统一）。严格判等小写的实现
 * 拿到 PT 侧的数据会**恒返回 null**——不报错、不告警，只是那个链接一个都不渲染，
 * 而"这个按钮怎么没出来"是最难从代码审查里看出来的一类缺陷。这里一律 toLowerCase 归一。
 */

/** 能拼出 TMDb 链接的最小信息。各页面的行对象字段名恰好都对得上，直接传行即可 */
export interface TmdbTarget {
  tmdbId?: string | number | null
  /** `tv`/`movie`，大小写不敏感（PT 侧存大写、重命名侧存小写） */
  mediaType?: string | null
}

/** 深链层级。季/集对电影一律忽略 */
export interface TmdbLinkOptions {
  /** 季号；0 是合法的（TMDb 上的 Specials） */
  season?: number | string | null
  /**
   * 集号，**必须是 TMDb 口径的集号**。
   *
   * 本项目的集号有三套（见 AGENTS.md「集号有三套」），本地季内相对号与 TMDb 主数据
   * 未必一致——长篇动画用的是绝对号（航海王第 23 季 = 1156..1181）。拿本地集号拼出来的
   * 深链会落到一个 TMDb 上不存在的集上，所以只有手里确实是 `tmdb_episode_number`
   * 时才传这个参数；拿不准就只传 season，落到季页面同样解决问题。
   */
  episode?: number | string | null
}

const BASE = 'https://www.themoviedb.org'

/** 解析成非负整数，拿不到给 null（空串、null、'abc'、小数、负数都算拿不到） */
function toIndex(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  return Number.isInteger(num) && num >= 0 ? num : null
}

/**
 * 拼 TMDb 条目链接，信息不足时返回 null（调用方据此决定渲染链接还是纯文本）。
 *
 * ```
 * tmdbUrl({ mediaType: 'TV', tmdbId: '79481' })                        // /tv/79481
 * tmdbUrl({ mediaType: 'TV', tmdbId: '79481' }, { season: 2 })         // /tv/79481/season/2
 * tmdbUrl({ mediaType: 'TV', tmdbId: '1' }, { season: 2, episode: 5 }) // /tv/1/season/2/episode/5
 * tmdbUrl({ mediaType: 'MOVIE', tmdbId: '1' }, { season: 0 })          // /movie/1（季集对电影恒忽略）
 * ```
 *
 * **判断是不是电影一律看 `mediaType`，不要看 `season`**：电影的 season 在库里恒为哨兵 0
 * （不用 null 否则唯一索引失效），而剧集的**特别篇也是第 0 季**，按 season===0 判会把
 * 特别篇一起当成电影——与后端同一条约定。
 */
export function tmdbUrl(target: TmdbTarget | null | undefined, options?: TmdbLinkOptions): string | null {
  if (!target) return null
  const id = target.tmdbId
  if (id === null || id === undefined || String(id).trim() === '') return null

  const kind = String(target.mediaType ?? '').toLowerCase()
  if (kind !== 'tv' && kind !== 'movie') return null

  const base = `${BASE}/${kind}/${String(id).trim()}`
  if (kind === 'movie') return base

  const season = toIndex(options?.season)
  if (season === null) return base

  // 集号没有季号是拼不出路径的；集从 1 起，0 不是合法集号（季可以是 0 = Specials）
  const episode = toIndex(options?.episode)
  if (episode === null || episode === 0) return `${base}/season/${season}`
  return `${base}/season/${season}/episode/${episode}`
}
