import { describe, it, expect } from 'vitest'
import { toRuleFns } from '../formRules'

/**
 * 这个转换器收口前在 14 个页面里各写一份、分化成 4 种实现，每份只覆盖「自己那个
 * composable 当前用到的规则种类」。它坏掉的方式是**静默的**：规则照常声明、表单照常
 * 提交，只是那条校验不存在了。所以这里逐种规则各钉一条。
 */
describe('toRuleFns', () => {
  const run = (rules: any[], value: any) => toRuleFns(rules).map((fn) => fn(value))

  it('没有规则时返回空数组，传 undefined 也不炸', () => {
    expect(toRuleFns()).toEqual([])
    expect(toRuleFns([])).toEqual([])
  })

  it('required 认得出 null / undefined / 空串，但 0 与 false 是合法值', () => {
    const r = [{ required: true, message: '不能为空' }]
    expect(run(r, null)).toEqual(['不能为空'])
    expect(run(r, undefined)).toEqual(['不能为空'])
    expect(run(r, '')).toEqual(['不能为空'])
    expect(run(r, 0)).toEqual([true])
    expect(run(r, false)).toEqual([true])
  })

  it('pattern 不匹配时报错', () => {
    const r = [{ pattern: /^https?:\/\//, message: '地址须以 http(s):// 开头' }]
    expect(run(r, 'ftp://x')).toEqual(['地址须以 http(s):// 开头'])
    expect(run(r, 'http://x')).toEqual([true])
  })

  it('数字上下限两侧都判', () => {
    const r = [{ type: 'number' as const, min: 1, max: 65535, message: '端口不合法' }]
    expect(run(r, 0)).toEqual(['端口不合法'])
    expect(run(r, 65536)).toEqual(['端口不合法'])
    expect(run(r, 8080)).toEqual([true])
  })

  it('非必填字段留空一律放行——Number("") === 0，不挡的话 min 会把可清空的字段锁死', () => {
    const r = [{ type: 'number' as const, min: 1, message: '不得小于 1' }]
    expect(run(r, '')).toEqual([true])
    expect(run(r, null)).toEqual([true])
  })

  it('必填 + 范围同时声明时，留空报的是「必填」而不是「不得小于」', () => {
    const r = [{ required: true, type: 'number' as const, min: 60, message: '轮询周期不得小于 60 秒' }]
    // 两条规则通常分开写，这里合并成一条是为了确认判定顺序：required 先于范围
    expect(run(r, '')).toEqual(['轮询周期不得小于 60 秒'])
    expect(run(r, 30)).toEqual(['轮询周期不得小于 60 秒'])
    expect(run(r, 600)).toEqual([true])
  })

  it('没写 message 时给得出可读的兜底文案', () => {
    expect(run([{ required: true }], '')).toEqual(['不能为空'])
    expect(run([{ pattern: /^a$/ }], 'b')).toEqual(['格式不正确'])
    expect(run([{ type: 'number' as const, min: 3 }], 1)).toEqual(['不得小于 3'])
    expect(run([{ type: 'number' as const, max: 3 }], 5)).toEqual(['不得大于 3'])
  })

  it('同一条规则同时带 pattern 与 number 约束时两者都生效', () => {
    const r = [{ pattern: /^\d+$/, type: 'number' as const, max: 10, message: '不合法' }]
    expect(run(r, 'abc')).toEqual(['不合法'])
    expect(run(r, '20')).toEqual(['不合法'])
    expect(run(r, '5')).toEqual([true])
  })
})
