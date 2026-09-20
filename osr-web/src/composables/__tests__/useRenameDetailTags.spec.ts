import { describe, it, expect, vi } from 'vitest'

vi.mock('../useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))

import { renameTags } from '../useRenameDetailList'
import { isSubtitleFile } from '../useStrmRecord'

describe('重命名明细的识别结果标签', () => {
  it('剧集：类型 + 补零的季集 + 画质信息，缺失的段不出现', () => {
    expect(renameTags({
      mediaType: 'tv', season: '1', episode: '5', resolution: '2160p', videoCodec: 'HEVC', releaseGroup: 'HHWEB'
    })).toEqual(['剧集', 'S01E05', '2160p', 'HEVC', 'HHWEB'])
  })

  it('电影不带季集', () => {
    expect(renameTags({ mediaType: 'movie', season: '1', resolution: '1080p' })).toEqual(['电影', '1080p'])
  })
})

describe('STRM 记录的文件类型图标', () => {
  it('按扩展名识别字幕，大小写不敏感', () => {
    expect(isSubtitleFile('a.SRT')).toBe(true)
    expect(isSubtitleFile('a.zh.ass')).toBe(true)
    expect(isSubtitleFile('a.mkv')).toBe(false)
    expect(isSubtitleFile(null)).toBe(false)
  })
})
