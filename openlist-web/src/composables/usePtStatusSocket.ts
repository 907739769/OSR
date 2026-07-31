import { onMounted, onUnmounted } from 'vue'
import Cookies from 'js-cookie'
import { message } from '@/composables/useMessage'
import { useUserStore } from '@/stores/user'

/** 下载记录状态推送事件：DownloadTrackService 的 markDownloading/complete/fail 三个状态推进点各推一条 */
export interface PtDownloadStatusEvent {
  type: 'download'
  downloadId: number
  subId: number
  episode: number
  state: string
  progress?: number
  failReason?: string
}

/** 订阅命中时间推送事件：SubscriptionEngine.handleGroup 推送成功后追加一条 */
export interface PtSubscriptionStatusEvent {
  type: 'subscription'
  subId: number
  lastMatchTime: string
}

export interface PtStatusSocketHandlers {
  onDownload?: (event: PtDownloadStatusEvent) => void
  onSubscription?: (event: PtSubscriptionStatusEvent) => void
}

/**
 * PT 订阅/下载记录实时状态推送：封装 WebSocket 连接生命周期，写法与
 * `views/monitor/log/realtime.vue` 的 connectWebSocket 一致——token 鉴权失败（收到
 * "unauthorized" 文本帧）不重连，普通断线 3 秒后自动重连。
 *
 * 默认在组件 onMounted 时自动连接、onUnmounted 时自动断开；若调用方本身不是在组件
 * setup() 中直接使用（onMounted 不会生效），可自行在合适的生命周期钩子里调用返回的
 * connect()/disconnect()。
 */
export function usePtStatusSocket(handlers: PtStatusSocketHandlers) {
  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let unauthorized = false

  function connect() {
    if (typeof WebSocket === 'undefined') return
    const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://'
    const host = window.location.host
    const token = Cookies.get('token') || ''
    const url = `${protocol}${host}/websocket/pt/status${token ? `?token=${token}` : ''}`

    ws = new WebSocket(url)

    ws.onmessage = (event: MessageEvent) => {
      if (event.data === 'unauthorized') {
        unauthorized = true
        const userStore = useUserStore()
        userStore.clearToken()
        message.error('登录已过期，请重新登录')
        window.location.href = '/login'
        return
      }
      let data: any
      try {
        data = JSON.parse(event.data)
      } catch (e) {
        console.error('解析 PT 状态推送消息失败', e)
        return
      }
      if (data.type === 'download') {
        handlers.onDownload?.(data)
      } else if (data.type === 'subscription') {
        handlers.onSubscription?.(data)
      }
    }

    ws.onclose = () => {
      if (unauthorized) return
      reconnectTimer = setTimeout(() => {
        connect()
      }, 3000)
    }
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.onclose = null
      ws.close()
      ws = null
    }
  }

  onMounted(() => {
    connect()
  })

  onUnmounted(() => {
    disconnect()
  })

  return { connect, disconnect }
}
