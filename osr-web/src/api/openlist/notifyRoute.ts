import request from '@/api/request'

/** 通知类型 */
export interface NotifyTypeMeta {
  code: string
  label: string
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
  routes: NotifyRouteItem[]
}

export function getNotifyMatrixApi() {
  return request.get<any, NotifyMatrix>('/openliststrm/notify-routes/matrix')
}

export function saveNotifyRoutesApi(items: NotifyRouteItem[]) {
  return request.post('/openliststrm/notify-routes', items)
}
