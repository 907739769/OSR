import { ref, computed } from 'vue'
import { message } from '@/composables/useMessage'
import {
  getNotifyMatrixApi, saveNotifyRoutesApi,
  type NotifyChannelMeta, type NotifyRouteItem, type NotifyTypeMeta
} from '@/api/openlist/notifyRoute'

/** 收件人范围选项，与后端 NotifyRoutePlus.SCOPE_* 一一对应 */
export const RECIPIENT_SCOPES = [
  { value: 'ADMIN', title: '仅管理员' },
  { value: 'OWNER', title: '仅订阅人' },
  { value: 'BOTH', title: '两者都发' }
]

/**
 * 通知路由矩阵：行是通知类型，列是渠道，格子里是「开关 + 收件人范围」。
 * PC 渲染成表格，移动端渲染成按类型分组的卡片，数据与操作完全共用。
 */
export function useNotifyRoute() {
  const loading = ref(false)
  const saving = ref(false)
  const types = ref<NotifyTypeMeta[]>([])
  const channels = ref<NotifyChannelMeta[]>([])

  /** key = 类型|渠道，值即将提交的行 */
  const cells = ref<Record<string, NotifyRouteItem>>({})

  const cellKey = (type: string, channel: string) => `${type}|${channel}`

  const load = async () => {
    loading.value = true
    try {
      const data = await getNotifyMatrixApi()
      types.value = data?.types || []
      channels.value = data?.channels || []

      const existing: Record<string, NotifyRouteItem> = {}
      for (const r of data?.routes || []) {
        existing[cellKey(r.notificationType, r.channel)] = r
      }
      // 后端只返回已有行；类型×渠道的笛卡尔积里缺的格子在这里补默认值。
      // 默认「开启」与后端「路由缺失按发送处理」保持一致，避免页面显示关闭、实际却在发
      const filled: Record<string, NotifyRouteItem> = {}
      for (const t of types.value) {
        for (const c of channels.value) {
          const k = cellKey(t.code, c.key)
          filled[k] = existing[k] || {
            notificationType: t.code, channel: c.key, enabled: true, recipientScope: 'ADMIN'
          }
        }
      }
      cells.value = filled
    } catch (e) {
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  const cellOf = (type: string, channel: string) => cells.value[cellKey(type, channel)]

  const save = async () => {
    saving.value = true
    try {
      await saveNotifyRoutesApi(Object.values(cells.value))
      message.success('已保存通知路由')
      await load()
    } catch (e) {
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  /** 某个渠道整列开/关，渠道多起来之后逐格点太累 */
  const toggleChannel = (channelKey: string, enabled: boolean) => {
    for (const t of types.value) {
      const cell = cellOf(t.code, channelKey)
      if (cell) cell.enabled = enabled
    }
  }

  /** 某个类型整行开/关 */
  const toggleType = (typeCode: string, enabled: boolean) => {
    for (const c of channels.value) {
      const cell = cellOf(typeCode, c.key)
      if (cell) cell.enabled = enabled
    }
  }

  const unconfiguredChannels = computed(() => channels.value.filter((c) => !c.configured))

  load()

  return {
    loading, saving, types, channels, cells,
    cellOf, load, save, toggleChannel, toggleType, unconfiguredChannels
  }
}
