import request from '@/api/request'

/** 通知类型 */
export interface NotifyTypeMeta {
  code: string
  label: string
  /** 什么时候会收到这类通知 */
  description: string
  /** 是否按高优先级推送（Bark 响铃、Gotify 弹窗） */
  urgent: boolean
}

/** 渠道，含页面要用的两个能力位 */
export interface NotifyChannelMeta {
  key: string
  name: string
  /** false 的渠道只有一个全局接收人，页面不展示收件人选项 */
  supportsDirectDelivery: boolean
  /** 未配置时页面给出提示 */
  configured: boolean
}

export interface NotifyRouteItem {
  notificationType: string
  channel: string
  enabled: boolean
  recipientScope: string
}

export interface NotifyMatrix {
  types: NotifyTypeMeta[]
  channels: NotifyChannelMeta[]
  /** 已由后端按「类型 × 渠道」补齐，缺行格子的默认值与实际发送行为一致 */
  routes: NotifyRouteItem[]
}

export function getNotifyMatrixApi() {
  return request.get<any, NotifyMatrix>('/openliststrm/notify-routes/matrix')
}

export function saveNotifyRoutesApi(items: NotifyRouteItem[]) {
  return request.post('/openliststrm/notify-routes', items)
}

/** 向某个渠道发一条测试消息。失败时后端返回具体原因，由 request.ts 的拦截器提示 */
export function sendNotifyTestApi(channel: string) {
  return request.post(`/openliststrm/notify-routes/test/${encodeURIComponent(channel)}`)
}
