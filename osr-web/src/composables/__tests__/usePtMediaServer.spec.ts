import { describe, it, expect, vi, beforeEach } from 'vitest'
import { confirm } from '@/composables/useConfirm'
import * as api from '@/api/openlist/ptMediaServer'
import { usePtMediaServer } from '@/composables/usePtMediaServer'

vi.mock('@/composables/useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))

vi.mock('@/composables/useConfirm', () => ({
  confirm: vi.fn()
}))

vi.mock('@/api/openlist/ptMediaServer', () => ({
  getPtMediaServerListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  addPtMediaServerApi: vi.fn().mockResolvedValue(undefined),
  updatePtMediaServerApi: vi.fn().mockResolvedValue(undefined),
  deletePtMediaServerApi: vi.fn().mockResolvedValue(undefined),
  testPtMediaServerApi: vi.fn(),
  listPtMediaServerUsersApi: vi.fn()
}))

/**
 * 「停用/删除最后一台启用中的媒体服务器」要多问一句。
 *
 * 媒体服务器是订阅「已入库」判定的唯一数据来源，关掉最后一台的后果是连锁的：进度不再推进、
 * 卡死在途集清扫整体跳过。而这两个操作原先的提示分别是模板文案「是否确认删除编号为…的数据项？」
 * 和一个**连确认框都没有**的单选按钮——后者尤其容易误触。
 *
 * 这些断言是那段警告文案唯一的守卫：去掉之后功能照常工作（确实删掉了 / 确实停用了），
 * 只是用户不知道自己按下的是什么。
 */
describe('usePtMediaServer 的「最后一台启用中」提醒', () => {
  const EMBY = { id: 1, name: 'emby', enabled: '1' }
  const JELLYFIN = { id: 2, name: 'jellyfin', enabled: '1' }
  const DISABLED = { id: 3, name: '停用的', enabled: '0' }

  /** 建实例并把列表塞成给定内容（getList 是异步的，这里直接写 taskList 更稳） */
  function given(list: any[]) {
    const ctx = usePtMediaServer({ autoLoad: false })
    ctx.taskList.value = list as any
    return ctx
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(confirm).mockResolvedValue(undefined as any)
  })

  const confirmText = () => (vi.mocked(confirm).mock.calls[0]?.[0] as any)?.message ?? ''

  it('删除唯一启用中的服务器时，确认框写出后果', async () => {
    const ctx = given([EMBY, DISABLED])

    await ctx.handleDelete(EMBY)

    expect(confirm).toHaveBeenCalled()
    expect(confirmText()).toContain('唯一启用中')
    expect(confirmText()).toContain('清扫')
  })

  it('还有另一台启用时不发出这个警告', async () => {
    const ctx = given([EMBY, JELLYFIN])

    await ctx.handleDelete(EMBY)

    expect(confirm).toHaveBeenCalled()
    expect(confirmText()).not.toContain('唯一启用中')
  })

  /** 删的是停用中的那台，与对账依据无关 */
  it('删除停用中的服务器不发出警告', async () => {
    const ctx = given([EMBY, DISABLED])

    await ctx.handleDelete(DISABLED)

    expect(confirmText()).not.toContain('唯一启用中')
  })

  it('确认文案里带服务器名字，不是「编号为 1 的数据项」', async () => {
    const ctx = given([EMBY, JELLYFIN])

    await ctx.handleDelete(EMBY)

    expect(confirmText()).toContain('emby')
    expect(confirmText()).not.toContain('数据项')
  })

  it('批量删除把全部启用中的一起删掉时也要警告', async () => {
    const ctx = given([EMBY, JELLYFIN, DISABLED])
    ctx.selectedIds.value = [1, 2] as any

    await ctx.handleDelete()

    expect(confirmText()).toContain('唯一启用中')
  })

  it('批量删除只删掉其中一台启用的，不警告', async () => {
    const ctx = given([EMBY, JELLYFIN, DISABLED])
    ctx.selectedIds.value = [1, 3] as any

    await ctx.handleDelete()

    expect(confirmText()).not.toContain('唯一启用中')
  })

  describe('停用', () => {
    /** 停用只是弹窗里的一个单选按钮，此前连确认框都没有，而后果与删除一样 */
    it('把唯一启用中的改成停用要先确认', async () => {
      const ctx = given([EMBY, DISABLED])
      ctx.form.value = { id: 1, name: 'emby', enabled: '0' } as any
      ctx.formRef.value = { validate: () => ({ valid: true }) } as any

      await ctx.submitForm()

      expect(confirm).toHaveBeenCalled()
      expect(confirmText()).toContain('唯一启用中')
      expect(api.updatePtMediaServerApi).toHaveBeenCalled()
    })

    it('用户在确认框点取消时不提交', async () => {
      vi.mocked(confirm).mockRejectedValue('cancel')
      const ctx = given([EMBY, DISABLED])
      ctx.form.value = { id: 1, name: 'emby', enabled: '0' } as any
      ctx.formRef.value = { validate: () => ({ valid: true }) } as any

      await ctx.submitForm()

      expect(api.updatePtMediaServerApi).not.toHaveBeenCalled()
    })

    it('还有另一台启用时，停用不额外确认', async () => {
      const ctx = given([EMBY, JELLYFIN])
      ctx.form.value = { id: 1, name: 'emby', enabled: '0' } as any
      ctx.formRef.value = { validate: () => ({ valid: true }) } as any

      await ctx.submitForm()

      expect(confirm).not.toHaveBeenCalled()
      expect(api.updatePtMediaServerApi).toHaveBeenCalled()
    })

    /** 从停用改成启用、或者根本没动状态，都不该被拦 */
    it('启用一台服务器时不确认', async () => {
      const ctx = given([DISABLED])
      ctx.form.value = { id: 3, name: '停用的', enabled: '1' } as any
      ctx.formRef.value = { validate: () => ({ valid: true }) } as any

      await ctx.submitForm()

      expect(confirm).not.toHaveBeenCalled()
      expect(api.updatePtMediaServerApi).toHaveBeenCalled()
    })
  })

  describe('用户列表', () => {
    it('拉取成功后填进 users', async () => {
      vi.mocked(api.listPtMediaServerUsersApi).mockResolvedValue([{ id: 'abc', name: 'Jack' }] as any)
      const ctx = given([])
      ctx.form.value = { url: 'http://x:8096', apiKey: 'k' } as any

      await ctx.handleLoadUsers()

      expect(ctx.users.value).toEqual([{ id: 'abc', name: 'Jack' }])
    })

    /** 没填地址就点，不该发请求 */
    it('地址为空时不发请求', async () => {
      const ctx = given([])
      ctx.form.value = { url: undefined, apiKey: 'k' } as any

      await ctx.handleLoadUsers()

      expect(api.listPtMediaServerUsersApi).not.toHaveBeenCalled()
    })

    /** 编辑已有记录时 apiKey 被后端脱敏为空，靠 id 让后端回填，不该被前端挡下 */
    it('编辑已有记录时 apiKey 为空也能拉取', async () => {
      vi.mocked(api.listPtMediaServerUsersApi).mockResolvedValue([] as any)
      const ctx = given([])
      ctx.form.value = { id: 1, url: 'http://x:8096', apiKey: undefined } as any

      await ctx.handleLoadUsers()

      expect(api.listPtMediaServerUsersApi).toHaveBeenCalled()
    })

    it('打开新增弹窗时清掉上一台的用户列表', () => {
      const ctx = given([])
      ctx.users.value = [{ id: 'abc', name: 'Jack' }]

      ctx.handleAdd('新增媒体服务器')

      expect(ctx.users.value).toEqual([])
    })
  })
})
