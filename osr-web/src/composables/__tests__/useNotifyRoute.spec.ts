import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'

vi.mock('@/composables/useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))
vi.mock('@/composables/useConfirm', () => ({ confirm: vi.fn() }))
vi.mock('@/router', () => ({
  default: { push: vi.fn() },
  getRoutePathForComponent: (k: string) => `/p/${k}`
}))
vi.mock('@/api/openlist/notifyRoute', () => ({
  getNotifyMatrixApi: vi.fn(),
  saveNotifyRoutesApi: vi.fn(),
  sendNotifyTestApi: vi.fn()
}))

import { useNotifyRoute, channelIcon } from '../useNotifyRoute'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { getNotifyMatrixApi, saveNotifyRoutesApi, sendNotifyTestApi } from '@/api/openlist/notifyRoute'

const flush = () => new Promise((r) => setTimeout(r, 0))

const TYPES = [
  { code: 'GENERAL', label: '系统告警', description: '故障', urgent: true },
  { code: 'EPISODE_OVERDUE', label: '缺集逾期', description: '逾期', urgent: false }
]
const CHANNELS = [
  { key: 'TELEGRAM', name: 'Telegram', supportsDirectDelivery: false, configured: true },
  { key: 'WECOM', name: '企业微信', supportsDirectDelivery: true, configured: true },
  { key: 'BARK', name: 'Bark', supportsDirectDelivery: false, configured: false }
]

function matrix() {
  const routes = []
  for (const t of TYPES) {
    for (const c of CHANNELS) {
      routes.push({
        notificationType: t.code,
        channel: c.key,
        enabled: true,
        recipientScope: c.supportsDirectDelivery ? 'OWNER' : 'ADMIN'
      })
    }
  }
  return { types: TYPES, channels: CHANNELS, routes }
}

async function setup() {
  const scope = effectScope()
  const api = scope.run(() => useNotifyRoute())!
  await flush()
  return { api, scope }
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(getNotifyMatrixApi as any).mockImplementation(async () => matrix())
  ;(saveNotifyRoutesApi as any).mockResolvedValue(undefined)
})

describe('useNotifyRoute', () => {
  /**
   * 缺行格子的默认值由后端给。原先前端自己补「仅管理员」，与企微缺行时的真实行为（仅订阅人）
   * 对不上，用户随手一保存就把缺集提醒改成了只发管理员
   */
  it('原样使用后端下发的格子，不再自己补默认值', async () => {
    const { api } = await setup()
    expect(api.cellOf('EPISODE_OVERDUE', 'WECOM')?.recipientScope).toBe('OWNER')
  })

  it('改动计数：改了再改回去不算未保存', async () => {
    const { api } = await setup()
    expect(api.isDirty.value).toBe(false)

    api.cellOf('GENERAL', 'WECOM')!.enabled = false
    api.cellOf('GENERAL', 'TELEGRAM')!.enabled = false
    expect(api.dirtyCount.value).toBe(2)
    expect(api.isCellDirty('GENERAL', 'WECOM')).toBe(true)

    api.cellOf('GENERAL', 'TELEGRAM')!.enabled = true
    expect(api.dirtyCount.value).toBe(1)
  })

  it('没有改动时保存不发请求', async () => {
    const { api } = await setup()
    await api.save()
    expect(saveNotifyRoutesApi).not.toHaveBeenCalled()
    expect(message.info).toHaveBeenCalled()
  })

  it('保存后以服务端数据为新基线，未保存计数归零', async () => {
    const { api } = await setup()
    api.cellOf('GENERAL', 'WECOM')!.enabled = false
    ;(getNotifyMatrixApi as any).mockImplementation(async () => {
      const m = matrix()
      m.routes.find((r) => r.notificationType === 'GENERAL' && r.channel === 'WECOM')!.enabled = false
      return m
    })

    await api.save()

    expect(saveNotifyRoutesApi).toHaveBeenCalledTimes(1)
    expect(api.isDirty.value).toBe(false)
    expect(api.cellOf('GENERAL', 'WECOM')?.enabled).toBe(false)
  })

  it('有改动时重新加载要先确认，取消则保留编辑', async () => {
    const { api } = await setup()
    api.cellOf('GENERAL', 'WECOM')!.enabled = false
    ;(confirm as any).mockRejectedValueOnce(new Error('cancel'))

    await api.reload()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(getNotifyMatrixApi).toHaveBeenCalledTimes(1)
    expect(api.cellOf('GENERAL', 'WECOM')?.enabled).toBe(false)
  })

  it('没有改动时重新加载不打扰用户', async () => {
    const { api } = await setup()
    await api.reload()
    expect(confirm).not.toHaveBeenCalled()
    expect(getNotifyMatrixApi).toHaveBeenCalledTimes(2)
  })

  describe('未配置渠道收起', () => {
    it('默认只显示已配置的渠道，展开后全部显示', async () => {
      const { api } = await setup()
      expect(api.visibleChannels.value.map((c) => c.key)).toEqual(['TELEGRAM', 'WECOM'])
      api.showUnconfigured.value = true
      expect(api.visibleChannels.value.map((c) => c.key)).toEqual(['TELEGRAM', 'WECOM', 'BARK'])
    })

    it('一个都没配置时全部显示，否则页面上什么都没有', async () => {
      (getNotifyMatrixApi as any).mockImplementation(async () => ({
        ...matrix(),
        channels: CHANNELS.map((c) => ({ ...c, configured: false }))
      }))
      const { api } = await setup()
      expect(api.canHideUnconfigured.value).toBe(false)
      expect(api.visibleChannels.value).toHaveLength(3)
    })
  })

  describe('整行 / 整列开关', () => {
    it('三态：全开 / 部分 / 全关', async () => {
      const { api } = await setup()
      expect(api.channelState('WECOM')).toBe('all')
      api.cellOf('GENERAL', 'WECOM')!.enabled = false
      expect(api.channelState('WECOM')).toBe('some')
      api.cellOf('EPISODE_OVERDUE', 'WECOM')!.enabled = false
      expect(api.channelState('WECOM')).toBe('none')
    })

    it('按当前态切换：全开 → 全关，部分 → 全开', async () => {
      const { api } = await setup()
      api.toggleChannel('WECOM')
      expect(api.channelState('WECOM')).toBe('none')

      api.cellOf('GENERAL', 'WECOM')!.enabled = true
      api.toggleChannel('WECOM')
      expect(api.channelState('WECOM')).toBe('all')
    })

    /** 收起的列被整行开关顺手改掉的话，用户看不到、却会被一起保存 */
    it('整行开关不动收起的渠道', async () => {
      const { api } = await setup()
      api.toggleType('GENERAL')
      expect(api.cellOf('GENERAL', 'TELEGRAM')?.enabled).toBe(false)
      expect(api.cellOf('GENERAL', 'WECOM')?.enabled).toBe(false)
      expect(api.cellOf('GENERAL', 'BARK')?.enabled).toBe(true)
    })
  })

  describe('发送测试', () => {
    it('成功：记下结果并提示', async () => {
      (sendNotifyTestApi as any).mockResolvedValue(undefined)
      const { api } = await setup()

      await api.testChannel('WECOM')

      expect(sendNotifyTestApi).toHaveBeenCalledWith('WECOM')
      expect(api.testResults.value.WECOM.ok).toBe(true)
      expect(api.testingChannel.value).toBeNull()
    })

    /** toast 几秒就消失，失败原因要留在渠道旁边 */
    it('失败：把后端给的原因留在渠道旁边', async () => {
      (sendNotifyTestApi as any).mockRejectedValue(new Error('企业微信返回错误 81013'))
      const { api } = await setup()

      await api.testChannel('WECOM')

      expect(api.testResults.value.WECOM).toEqual({ ok: false, text: '企业微信返回错误 81013' })
      expect(api.testingChannel.value).toBeNull()
    })
  })

  it('渠道图标：品牌渠道用品牌图标，认不出的退回铃铛', () => {
    expect(channelIcon('TELEGRAM')).toBe('brand-telegram')
    expect(channelIcon('WECOM')).toBe('brand-wecom')
    expect(channelIcon('SOMETHING_NEW')).toBe('bell')
  })
})
