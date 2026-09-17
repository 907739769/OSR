import { describe, it, expect } from 'vitest'
import { canRetryCopy } from '../useCopyRecord'

describe('canRetryCopy', () => {
  // 与后端 CopyServiceImpl#RETRYABLE_STATUSES 一致，两边漂移的表现是「按钮能点、点了报错」或「该重试的没按钮」
  it('失败与未知可以重试', () => {
    expect(canRetryCopy('2')).toBe(true)
    expect(canRetryCopy('4')).toBe(true)
  })

  it('处理中与已成功不能重试', () => {
    expect(canRetryCopy('1')).toBe(false)
    expect(canRetryCopy('3')).toBe(false)
  })
})
