import { describe, it, expect } from 'vitest'
import { tmdbUrl } from '../tmdbLink'

describe('TMDb 条目链接', () => {
  it('按媒体类型拼，缺类型或缺 id 时不给', () => {
    expect(tmdbUrl({ mediaType: 'tv', tmdbId: '79481' })).toBe('https://www.themoviedb.org/tv/79481')
    expect(tmdbUrl({ mediaType: 'movie', tmdbId: '550' })).toBe('https://www.themoviedb.org/movie/550')
    expect(tmdbUrl({ mediaType: 'tv' })).toBeNull()
    expect(tmdbUrl({ tmdbId: '1' })).toBeNull()
    expect(tmdbUrl({ tmdbId: '1', mediaType: 'person' })).toBeNull()
    expect(tmdbUrl(null)).toBeNull()
  })

  /**
   * 这一条是这个模块存在的主要理由：rename_detail.media_type 存小写、
   * pt_subscription / pt_auto_add_log 存大写，收口前的实现严格判等小写，
   * 拿到 PT 侧的数据会恒返回 null——不报错，只是链接一个都不渲染。
   */
  it('媒体类型大小写不敏感（PT 侧存 TV/MOVIE，重命名侧存 tv/movie）', () => {
    expect(tmdbUrl({ mediaType: 'TV', tmdbId: '79481' })).toBe('https://www.themoviedb.org/tv/79481')
    expect(tmdbUrl({ mediaType: 'MOVIE', tmdbId: '550' })).toBe('https://www.themoviedb.org/movie/550')
  })

  it('传了季号就深链到季，季 0 是特别篇、同样是合法路径', () => {
    expect(tmdbUrl({ mediaType: 'TV', tmdbId: '1' }, { season: 23 }))
      .toBe('https://www.themoviedb.org/tv/1/season/23')
    expect(tmdbUrl({ mediaType: 'TV', tmdbId: '1' }, { season: 0 }))
      .toBe('https://www.themoviedb.org/tv/1/season/0')
    expect(tmdbUrl({ mediaType: 'TV', tmdbId: '1' }, { season: 2, episode: 5 }))
      .toBe('https://www.themoviedb.org/tv/1/season/2/episode/5')
  })

  /**
   * 电影的 season 在库里恒为哨兵 0（不用 null 否则唯一索引失效），
   * 而剧集的特别篇也是第 0 季——判断是不是电影一律看 mediaType，不要看 season。
   */
  it('电影忽略季集，不会拼出 /movie/x/season/0', () => {
    expect(tmdbUrl({ mediaType: 'MOVIE', tmdbId: '550' }, { season: 0, episode: 1 }))
      .toBe('https://www.themoviedb.org/movie/550')
  })

  it('季集拿不到有效值时退回上一层，不拼出半截路径', () => {
    const tv = { mediaType: 'TV', tmdbId: '1' }
    expect(tmdbUrl(tv, { season: null, episode: 5 })).toBe('https://www.themoviedb.org/tv/1')
    expect(tmdbUrl(tv, { season: '', episode: 5 })).toBe('https://www.themoviedb.org/tv/1')
    expect(tmdbUrl(tv, { season: 'abc' })).toBe('https://www.themoviedb.org/tv/1')
    expect(tmdbUrl(tv, { season: -1 })).toBe('https://www.themoviedb.org/tv/1')
    // 集从 1 起：0 不是合法集号（季可以是 0），落回季页面
    expect(tmdbUrl(tv, { season: 2, episode: 0 })).toBe('https://www.themoviedb.org/tv/1/season/2')
    expect(tmdbUrl(tv, { season: 2, episode: null })).toBe('https://www.themoviedb.org/tv/1/season/2')
  })

  it('id 为空串/纯空白不算有，数字 id 照样认', () => {
    expect(tmdbUrl({ mediaType: 'tv', tmdbId: '' })).toBeNull()
    expect(tmdbUrl({ mediaType: 'tv', tmdbId: '   ' })).toBeNull()
    expect(tmdbUrl({ mediaType: 'tv', tmdbId: 79481 })).toBe('https://www.themoviedb.org/tv/79481')
  })
})
