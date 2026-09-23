import { describe, it, expect } from 'vitest'
import {
  validatePathMapping, transferConditionText, formatTransferSize, transferStateText, transferStateType
} from '../usePtTransferRule'

describe('保存路径映射的前端校验', () => {
  it('留空与合法配置通过', () => {
    expect(validatePathMapping(undefined)).toBe(true)
    expect(validatePathMapping('  ')).toBe(true)
    expect(validatePathMapping('[{"from":"/downloads","to":"/data/downloads"}]')).toBe(true)
  })

  /**
   * 后端运行期对坏配置是宽容的（退化成不映射），不在保存时拦下的话，
   * 写错的 JSON 要等转移校验失败、回滚之后才会被怀疑到
   */
  it('非法 JSON、不是数组、缺 from/to 都给出可读的错误', () => {
    expect(validatePathMapping('{from:/a')).toContain('JSON')
    expect(validatePathMapping('{"from":"/a","to":"/b"}')).toContain('数组')
    expect(validatePathMapping('[{"from":"/a"}]')).toContain('第 1 项')
    expect(validatePathMapping('["/a"]')).toContain('第 1 项')
  })
})

describe('转移规则的展示文案', () => {
  it('条件压成一行人话_全空时说明是全部已完成的种子', () => {
    expect(transferConditionText({ minSeedHours: 72, minSizeGb: 1, maxSizeGb: 50, excludeTags: 'keep' }))
      .toBe('做满 72 小时，1~50 GB，排除 keep')
    expect(transferConditionText({ minSeedHours: 0 })).toBe('全部已完成的种子')
  })

  it('体积按 GB / MB 显示', () => {
    expect(formatTransferSize(0)).toBe('-')
    expect(formatTransferSize(1.5 * 1024 ** 3)).toBe('1.50 GB')
    expect(formatTransferSize(300 * 1024 ** 2)).toBe('300 MB')
  })

  it('状态文案与颜色来自同一份选项_未知状态原样显示', () => {
    expect(transferStateText('VERIFYING')).toBe('校验中')
    expect(transferStateType('FAILED')).toBe('error')
    expect(transferStateText('NEW_STATE')).toBe('NEW_STATE')
  })
})
