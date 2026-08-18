import { describe, it, expect } from 'vitest'
import { GB, bytesToGb, gbToBytes } from '../sizeUnits'

describe('体积单位换算', () => {
  it('小数 GB 能原样往返', () => {
    expect(gbToBytes(1.5)).toBe(1.5 * GB)
    expect(bytesToGb(1.5 * GB)).toBe(1.5)
    expect(bytesToGb(gbToBytes(0.5))).toBe(0.5)
    expect(bytesToGb(gbToBytes(12.34))).toBe(12.34)
  })

  it('小于 1GB 的阈值不能被抹成 0——0 在过滤规则里是「不限」', () => {
    expect(bytesToGb(500 * 1024 * 1024)).toBeGreaterThan(0)
    expect(bytesToGb(700 * 1024 * 1024)).toBeCloseTo(0.68, 2)
  })

  it('提交值恒为整数字节（后端字段是 Long）', () => {
    expect(Number.isInteger(gbToBytes(0.01))).toBe(true)
    expect(Number.isInteger(gbToBytes(3.33))).toBe(true)
  })

  it('空值/非法值/负数一律按 0（不限）处理', () => {
    expect(bytesToGb(null)).toBe(0)
    expect(bytesToGb(undefined)).toBe(0)
    expect(bytesToGb(-1)).toBe(0)
    expect(gbToBytes(null)).toBe(0)
    expect(gbToBytes(undefined)).toBe(0)
    expect(gbToBytes(Number.NaN)).toBe(0)
    expect(gbToBytes(-2)).toBe(0)
  })
})
