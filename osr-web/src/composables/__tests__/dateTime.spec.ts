import { describe, it, expect } from 'vitest'
import { formatDateTime, formatDuration } from '../dateTime'

describe('formatDateTime', () => {
  it('按后端格式输出绝对时间', () => {
    expect(formatDateTime('2026-09-20 03:05:09')).toBe('2026-09-20 03:05:09')
  })

  it('可以省掉年份（列表里的「下次执行」用这个形态）', () => {
    expect(formatDateTime('2026-09-20 03:05:09', '-', false)).toBe('09-20 03:05:09')
  })

  it('空值走占位符', () => {
    expect(formatDateTime(null)).toBe('-')
    expect(formatDateTime(undefined)).toBe('-')
    expect(formatDateTime('', '从未执行')).toBe('从未执行')
  })

  it('解析不出来的串原样返回，不显示 NaN', () => {
    expect(formatDateTime('不是时间')).toBe('不是时间')
  })

  // 这条是这个模块存在的理由：`new Date('2026-09-20 03:00:00')` 在 iOS Safari 上是
  // Invalid Date，必须换成 'T'。漏掉不会报错，只会让时间在手机上原样显示成后端那串
  it('中间是空格的时间串也要解析得出来（iOS Safari）', () => {
    // 判据要能区分「真的解析了」与「解析失败后原样返回」：带年份时两者的输出恰好一样，
    // 分不出来。去掉年份就分得出——解析失败的分支返回的是完整原串
    expect(formatDateTime('2026-09-20 03:05:09', '-', false)).toBe('09-20 03:05:09')
    expect(formatDateTime('2026-09-20 03:05:09')).toBe(formatDateTime('2026-09-20T03:05:09'))
  })
})

describe('formatDuration', () => {
  it('分钟级', () => {
    expect(formatDuration('2026-09-20 03:00:00', '2026-09-20 03:02:30')).toBe('2分30秒')
  })

  it('秒级带一位小数', () => {
    expect(formatDuration('2026-09-20 03:00:00', '2026-09-20 03:00:07')).toBe('7.0秒')
  })

  it('毫秒级', () => {
    expect(formatDuration('2026-09-20T03:00:00.000', '2026-09-20T03:00:00.120')).toBe('120毫秒')
  })

  // 20260798 迁移之前的存量记录没有 start_time/end_time，这一列只能留空
  it('任一侧缺失返回占位符', () => {
    expect(formatDuration(null, '2026-09-20 03:00:00')).toBe('-')
    expect(formatDuration('2026-09-20 03:00:00', null)).toBe('-')
    expect(formatDuration(null, null)).toBe('-')
  })
})
