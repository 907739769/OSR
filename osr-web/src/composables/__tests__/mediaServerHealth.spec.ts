import { describe, it, expect } from 'vitest'
import { mediaServerHealth } from '../mediaServerHealth'
import { formatRelativeTime } from '../relativeTime'

/**
 * 媒体服务器连通状态的展示判据，PC 与移动端两张卡片共用这一份。
 *
 * 最要紧的一条是「还没用到过」不等于「不通」：把两者混成一个红色徽章，一台刚添的、
 * 完全正常的服务器在页面上看起来就是坏的。后端的 `PtMediaServerPlus#lastCheckFailed()`
 * 是同一条判据，两边一起改。
 */
describe('mediaServerHealth', () => {
  const NOW = new Date('2026-09-20T12:00:00').getTime()

  it('从未被用到过时是中性态，不是异常', () => {
    const health = mediaServerHealth({ lastCheckOk: null, lastCheckTime: null, lastCheckError: null })

    expect(health.type).toBe('default')
    expect(health.text).toBe('尚未使用')
    expect(health.showError).toBe(false)
  })

  it('字段整个缺失时也走中性态（后端刚加这三列、存量行全是 null）', () => {
    expect(mediaServerHealth({}).type).toBe('default')
  })

  it('连通时是 success 且不单独占一行写原因', () => {
    const health = mediaServerHealth({
      lastCheckOk: '1',
      lastCheckTime: '2026-09-20 11:58:00',
      lastCheckError: null
    })

    expect(health.type).toBe('success')
    expect(health.text).toContain('正常')
    expect(health.showError).toBe(false)
  })

  it('不通时是 error，并要求把原因单独写一行', () => {
    const health = mediaServerHealth({
      lastCheckOk: '0',
      lastCheckTime: '2026-09-20 11:58:00',
      lastCheckError: '媒体服务器返回 HTTP 401'
    })

    expect(health.type).toBe('error')
    expect(health.text).toContain('异常')
    // 移动端没有 hover，原因只放 tooltip 等于没写
    expect(health.showError).toBe(true)
    expect(health.detail).toContain('媒体服务器返回 HTTP 401')
  })

  it('没有时间戳时只显示状态词，不显示一个空的分隔符', () => {
    expect(mediaServerHealth({ lastCheckOk: '1' }).text).toBe('正常')
    expect(mediaServerHealth({ lastCheckOk: '0' }).text).toBe('异常')
  })

  describe('formatRelativeTime', () => {
    it('按分钟/小时/天分档', () => {
      expect(formatRelativeTime('2026-09-20 11:59:30', NOW)).toBe('刚刚')
      expect(formatRelativeTime('2026-09-20 11:57:00', NOW)).toBe('3 分钟前')
      expect(formatRelativeTime('2026-09-20 09:00:00', NOW)).toBe('3 小时前')
      expect(formatRelativeTime('2026-09-17 12:00:00', NOW)).toBe('3 天前')
    })

    it('空值返回空串，解析不出来时原样返回', () => {
      expect(formatRelativeTime(null)).toBe('')
      expect(formatRelativeTime('')).toBe('')
      expect(formatRelativeTime('不是时间')).toBe('不是时间')
    })

    /** 后端给的是 `yyyy-MM-dd HH:mm:ss`，Safari 不认中间那个空格 */
    it('带空格的后端时间格式能解析', () => {
      expect(formatRelativeTime('2026-09-20 11:57:00', NOW)).not.toBe('2026-09-20 11:57:00')
    })
  })
})
