import { describe, it, expect, vi } from 'vitest'

// osrCssVar 在 jsdom 里读不到真实令牌，桩成按令牌名返回可辨认的值：
// 这几条用例关心的是「取了哪个令牌」，不是具体色值
vi.mock('@/composables/useThemeMode', () => ({
  osrCssVar: (name: string) => `var(${name})`
}))

import {
  buildFailReasonOption,
  buildIndexerOption,
  buildRejectReasonOption,
  buildStatCards,
  buildTrendOption,
  failReasonColor
} from '@/composables/usePtStats'

describe('失败原因配色', () => {
  /**
   * 旧实现从首页状态卡抄了一个「名字里带不带『失败』」的猜色函数，而失败原因文案
   * 一个都命中不了，于是整张饼图恒为同一个红色。这条用例钉住「不同分类不同颜色」。
   */
  it('不同失败分类给出不同颜色', () => {
    const colors = ['TORRENT_NOT_FOUND', 'ZOMBIE_TIMEOUT', 'NO_TARGET_EPISODE', 'METADATA_TIMEOUT', 'OTHER']
      .map(failReasonColor)
    expect(new Set(colors).size).toBe(5)
  })

  it('颜色走设计令牌而不是写死的十六进制，否则暗色主题下不跟着变', () => {
    expect(failReasonColor('ZOMBIE_TIMEOUT')).toContain('--osr-')
  })

  it('未知码落到中性色而不是抛错', () => {
    expect(failReasonColor('SOMETHING_NEW')).toBe(failReasonColor('OTHER'))
    expect(failReasonColor(null)).toBe(failReasonColor('OTHER'))
  })
})

describe('统计卡', () => {
  /** 取数失败时显示 `--`：一排 0 会被读成「系统很干净」 */
  it('无数据时全部显示两横杠而不是0', () => {
    const cards = buildStatCards(null)
    expect(cards).toHaveLength(6)
    expect(cards.every(c => c.value === '--')).toBe(true)
  })

  it('有数据时成功率带百分号_没有下载记录时成功率为两横杠', () => {
    const withRecords = buildStatCards({
      totalSubscriptions: 20, activeSubscriptions: 15, totalDownloadRecords: 100,
      completedCount: 80, failedCount: 10, successRate: 80, avgDurationMinutes: 45.4
    })
    expect(withRecords.find(c => c.key === 'successRate')!.value).toBe('80%')
    expect(withRecords.find(c => c.key === 'failedCount')!.value).toBe(10)
    expect(withRecords.find(c => c.key === 'avgDuration')!.value).toBe('45 分钟')

    const empty = buildStatCards({
      totalSubscriptions: 0, activeSubscriptions: 0, totalDownloadRecords: 0,
      completedCount: 0, failedCount: 0, successRate: 0, avgDurationMinutes: 0
    })
    expect(empty.find(c => c.key === 'successRate')!.value).toBe('--')
    expect(empty.find(c => c.key === 'avgDuration')!.value).toBe('--')
  })
})

describe('图表选项', () => {
  const trend = [
    { date: '2026-09-01', pushedCount: 3, completedCount: 2, failedCount: 1, avgDurationMinutes: 30 },
    { date: '2026-09-02', pushedCount: 1, completedCount: 0, failedCount: 0, avgDurationMinutes: null }
  ]

  it('加载失败与暂无数据是两种空态，不能混成一种', () => {
    expect((buildTrendOption(null, true) as any).title.text).toBe('加载失败')
    expect((buildTrendOption([], false) as any).title.text).toBe('暂无数据')
  })

  /** 平均耗时后端一直在算也一直在传，此前两端都没画，等于每次白算一次 AVG */
  it('趋势图把平均耗时画在第二根Y轴上', () => {
    const option: any = buildTrendOption(trend)
    expect(option.series).toHaveLength(4)
    const avg = option.series.find((s: any) => s.name === '平均耗时')
    expect(avg.yAxisIndex).toBe(1)
    expect(Array.isArray(option.yAxis)).toBe(true)
  })

  it('全程没有平均耗时时不画第二根轴', () => {
    const option: any = buildTrendOption(trend.map(p => ({ ...p, avgDurationMinutes: null })))
    expect(option.series).toHaveLength(3)
    expect(Array.isArray(option.yAxis)).toBe(false)
  })

  /** 只跑过 3 条日志的站点和跑过 300 条的，在百分比条上视觉权重一样，必须把样本量说出来 */
  it('索引器命中率的tooltip带样本量', () => {
    const option: any = buildIndexerOption([
      { indexerId: 1, indexerName: '站点A', acceptedCount: 3, rejectedCount: 0, hitRate: 1, hasData: true },
      { indexerId: 2, indexerName: '站点B', acceptedCount: 0, rejectedCount: 0, hitRate: 0, hasData: false }
    ])
    const text = option.tooltip.formatter([{ dataIndex: 0, marker: '', seriesName: '通过', value: 100 }])
    expect(text).toContain('站点A')
    expect(text).toContain('3 / 3')
    // hasData=false 的索引器不进图，只在图下方列名字
    expect(option.yAxis.data).toEqual(['站点A'])
  })

  it('索引器全都没有数据时给空态而不是一张空轴', () => {
    expect((buildIndexerOption([
      { indexerId: 2, indexerName: '站点B', acceptedCount: 0, rejectedCount: 0, hitRate: 0, hasData: false }
    ]) as any).title.text).toBe('暂无数据')
  })

  it('失败原因按码取色_同一图内不同分类颜色不同', () => {
    const option: any = buildFailReasonOption([
      { code: 'ZOMBIE_TIMEOUT', reason: '下载超时', count: 12 },
      { code: 'NO_TARGET_EPISODE', reason: '无目标集', count: 5 }
    ])
    const colors = option.series[0].data.map((d: any) => d.itemStyle.color)
    expect(new Set(colors).size).toBe(2)
  })

  it('淘汰原因按计数升序排列_最能挡的那条排在顶部', () => {
    const option: any = buildRejectReasonOption([
      { code: 'NOT_FREE', reason: '非免费种', count: 98 },
      { code: 'LOW_SEEDERS', reason: '做种数不足', count: 4 }
    ])
    expect(option.yAxis.data).toEqual(['做种数不足', '非免费种'])
  })
})
