import { ref, computed, getCurrentInstance, getCurrentScope, onScopeDispose } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import router, { getRoutePathForComponent } from '@/router'
import {
  getNotifyMatrixApi, saveNotifyRoutesApi, sendNotifyTestApi,
  type NotifyChannelMeta, type NotifyRouteItem, type NotifyTypeMeta
} from '@/api/openlist/notifyRoute'

/** 收件人范围选项，与后端 NotifyRoutePlus.SCOPE_* 一一对应 */
export const RECIPIENT_SCOPES = [
  { value: 'ADMIN', title: '仅管理员' },
  { value: 'OWNER', title: '仅订阅人' },
  { value: 'BOTH', title: '两者都发' }
]

/**
 * 渠道图标。Telegram / 企业微信用品牌图标（它们本身就承担「走哪个渠道」的识别功能，
 * 见 plugins/lucideIcons.ts），其余用语义最近的 lucide 图标。
 * 认不出的渠道退回铃铛——新增渠道时后端注册即出现一列，不会因为这里漏登记而缺图标位。
 */
const CHANNEL_ICONS: Record<string, string> = {
  TELEGRAM: 'brand-telegram',
  WECOM: 'brand-wecom',
  WEBHOOK: 'webhook',
  BARK: 'smartphone',
  GOTIFY: 'server'
}
export const channelIcon = (key: string) => CHANNEL_ICONS[key] ?? 'bell'

/** 整行 / 整列的勾选态：全开、全关、部分开 */
export type ToggleState = 'all' | 'none' | 'some'

export interface ChannelTestResult {
  ok: boolean
  text: string
}

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
  /** 加载时每格的签名，与当前值比对得出「哪些格子改过」 */
  const baseline = ref<Record<string, string>>({})

  const cellKey = (type: string, channel: string) => `${type}|${channel}`
  const signature = (c: NotifyRouteItem) => `${c.enabled ? 1 : 0}|${c.recipientScope}`

  const load = async () => {
    loading.value = true
    try {
      const data = await getNotifyMatrixApi()
      types.value = data?.types || []
      channels.value = data?.channels || []
      // 后端已按「类型 × 渠道」补齐，缺行格子的默认值与实际发送行为一致。
      // 前端不要再自己补：原先这里补的「仅管理员」与企微缺行时的真实行为（仅订阅人）对不上，
      // 用户随手一保存就把缺集提醒改成了只发管理员
      const next: Record<string, NotifyRouteItem> = {}
      const base: Record<string, string> = {}
      for (const r of data?.routes || []) {
        const k = cellKey(r.notificationType, r.channel)
        next[k] = { ...r }
        base[k] = signature(r)
      }
      cells.value = next
      baseline.value = base
    } catch (e) {
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  const cellOf = (type: string, channel: string): NotifyRouteItem | undefined =>
    cells.value[cellKey(type, channel)]

  // ---- 未保存的修改 ----
  const dirtyCount = computed(() =>
    Object.entries(cells.value).filter(([k, c]) => baseline.value[k] !== signature(c)).length)
  const isDirty = computed(() => dirtyCount.value > 0)
  const isCellDirty = (type: string, channel: string) => {
    const c = cellOf(type, channel)
    return !!c && baseline.value[cellKey(type, channel)] !== signature(c)
  }

  const confirmDiscard = async (action: string) => {
    if (!isDirty.value) return true
    try {
      await confirm({
        title: '有未保存的修改',
        message: `还有 ${dirtyCount.value} 处修改没有保存，${action}后这些修改会丢失。`,
        confirmText: `仍然${action}`,
        cancelText: '取消',
        type: 'warning'
      })
      return true
    } catch {
      return false
    }
  }

  /** 「重新加载」会用服务端数据覆盖页面，有改动时先问一句 */
  const reload = async () => {
    if (await confirmDiscard('重新加载')) await load()
  }

  const save = async () => {
    if (!isDirty.value) {
      message.info('没有需要保存的修改')
      return
    }
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

  // ---- 渠道可见性 ----
  const unconfiguredChannels = computed(() => channels.value.filter((c) => !c.configured))
  const showUnconfigured = ref(false)
  /** 有已配置的渠道、也有未配置的，才有「收起未配置」这回事 */
  const canHideUnconfigured = computed(() =>
    unconfiguredChannels.value.length > 0 && unconfiguredChannels.value.length < channels.value.length)
  /**
   * 页面上实际展示的渠道。未配置的默认收起：5 列里常常只有一两列在用，
   * 其余几列全是开着却不会发送的开关，只会稀释真正有用的那几列。
   * 一个都没配置时全部展示，否则页面上什么都没有。
   */
  const visibleChannels = computed(() =>
    showUnconfigured.value || !canHideUnconfigured.value
      ? channels.value
      : channels.value.filter((c) => c.configured))

  // ---- 整行 / 整列开关 ----
  const stateOf = (list: (NotifyRouteItem | undefined)[]): ToggleState => {
    const present = list.filter((c): c is NotifyRouteItem => !!c)
    const on = present.filter((c) => c.enabled).length
    if (on === 0) return 'none'
    return on === present.length ? 'all' : 'some'
  }

  const channelState = (channelKey: string) => stateOf(types.value.map((t) => cellOf(t.code, channelKey)))
  /** 整行只统计、只作用于看得见的渠道：收起的列被顺手改掉，用户是看不到的 */
  const typeState = (typeCode: string) => stateOf(visibleChannels.value.map((c) => cellOf(typeCode, c.key)))

  /** 某个渠道整列开/关。不传 enabled 时按当前态切换：全开 → 全关，其余 → 全开 */
  const toggleChannel = (channelKey: string, enabled?: boolean) => {
    const next = enabled ?? channelState(channelKey) !== 'all'
    for (const t of types.value) {
      const cell = cellOf(t.code, channelKey)
      if (cell) cell.enabled = next
    }
  }

  /** 某个类型整行开/关，规则同上 */
  const toggleType = (typeCode: string, enabled?: boolean) => {
    const next = enabled ?? typeState(typeCode) !== 'all'
    for (const c of visibleChannels.value) {
      const cell = cellOf(typeCode, c.key)
      if (cell) cell.enabled = next
    }
  }

  // ---- 发送测试 ----
  const testingChannel = ref<string | null>(null)
  const testResults = ref<Record<string, ChannelTestResult>>({})

  /**
   * 「已配置」只说明地址/密钥填了，不说明填对了。测试绕过路由，直接问这个渠道通不通；
   * 失败原因由后端给（request.ts 已 toast），这里再留一份在渠道旁边，toast 消失后还看得到
   */
  const testChannel = async (channelKey: string) => {
    testingChannel.value = channelKey
    try {
      await sendNotifyTestApi(channelKey)
      testResults.value = { ...testResults.value, [channelKey]: { ok: true, text: '已发送，请到客户端查看' } }
      message.success('测试消息已发送')
    } catch (e) {
      const text = e instanceof Error ? e.message : '发送失败'
      testResults.value = { ...testResults.value, [channelKey]: { ok: false, text } }
    } finally {
      testingChannel.value = null
    }
  }

  // ---- 去参数设置配置渠道 ----
  /** 没有「参数设置」菜单权限的用户拿到 null，页面据此不显示入口 */
  const configPath = computed(() => getRoutePathForComponent('system/config/index'))
  const goConfig = () => {
    if (configPath.value) router.push(configPath.value)
  }

  // 离开页面前确认。测试里直接调用、不在组件 setup 里时不挂（onBeforeRouteLeave 需要组件实例）
  if (getCurrentInstance()) {
    onBeforeRouteLeave(() => confirmDiscard('离开'))
  }
  // 刷新 / 关标签页走的是浏览器自己的确认框，文案由浏览器决定
  const onBeforeUnload = (e: BeforeUnloadEvent) => {
    if (!isDirty.value) return
    e.preventDefault()
    e.returnValue = ''
  }
  if (getCurrentScope() && typeof window !== 'undefined') {
    window.addEventListener('beforeunload', onBeforeUnload)
    onScopeDispose(() => window.removeEventListener('beforeunload', onBeforeUnload))
  }

  load()

  return {
    loading, saving, types, channels, cells,
    cellOf, load, reload, save,
    dirtyCount, isDirty, isCellDirty,
    unconfiguredChannels, showUnconfigured, canHideUnconfigured, visibleChannels,
    channelState, typeState, toggleChannel, toggleType,
    testingChannel, testResults, testChannel,
    configPath, goConfig
  }
}
