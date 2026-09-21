import { describe, it, expect } from 'vitest'
import { splitCsv, joinCsv, moveItem, mergeOrder } from '../orderList'

describe('orderList', () => {
  it('splitCsv 去空白、去空项', () => {
    expect(splitCsv(' 2160p, ,1080p ,')).toEqual(['2160p', '1080p'])
    expect(splitCsv(null)).toEqual([])
    expect(joinCsv(['a', 'b'])).toBe('a,b')
  })

  it('moveItem 返回新数组、越界时原样', () => {
    const list = ['a', 'b', 'c']
    expect(moveItem(list, 1, -1)).toEqual(['b', 'a', 'c'])
    expect(moveItem(list, 2, 1)).toEqual(['a', 'b', 'c'])
    expect(list).toEqual(['a', 'b', 'c'])
  })

  it('mergeOrder 已配置的在前，不认识的丢掉，新增维度补到末尾', () => {
    // 后端新增维度后存量配置里没有它，不补的话它在页面上直接消失
    expect(mergeOrder('SEEDERS,BOGUS,RESOLUTION', ['RESOLUTION', 'SEEDERS', 'HR'])).toEqual([
      'SEEDERS', 'RESOLUTION', 'HR'
    ])
  })
})
