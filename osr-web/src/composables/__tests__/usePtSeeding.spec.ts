import { describe, it, expect } from 'vitest'
import { buildSeedingTrendOption } from '../usePtSeeding'

const GB = 1024 * 1024 * 1024

describe('buildSeedingTrendOption', () => {
  it('算不出上传量的那天柱子留空，不画成 0', () => {
    const option: any = buildSeedingTrendOption([
      { date: '2026-09-01', uploaded: null, seedingSize: 2 * GB },
      { date: '2026-09-02', uploaded: 1.5 * GB, seedingSize: 3 * GB }
    ])
    expect(option.series[0].data).toEqual(['-', 1.5])
    expect(option.series[1].data).toEqual([2, 3])
    expect(option.series[1].yAxisIndex).toBe(1)
    expect(option.xAxis.data).toEqual(['09-01', '09-02'])
  })

  it('还没有任何快照时给出说明而不是一张空坐标系', () => {
    const option: any = buildSeedingTrendOption([{ date: '2026-09-01', uploaded: null, seedingSize: 0 }])
    expect(option.series).toEqual([])
    expect(JSON.stringify(option)).toContain('暂无快照')
  })

  it('加载失败如实说', () => {
    expect(JSON.stringify(buildSeedingTrendOption(null, true))).toContain('加载失败')
  })
})
