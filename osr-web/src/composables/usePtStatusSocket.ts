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
  // access token 过期属正常现象（2 小时），此时应先尝试用 refreshToken 静默换新再重连；
  // 只有换新本身失败（refreshToken 也过期/无效）才是真正需要重新登录的场景。
  // 用这个标记避免"刷新成功但服务端仍拒绝"时无限重试；跨连接共享，换新成功后重置。
  let refreshedOnce = false

  function forceLogout() {
    const userStore = useUserStore()
    userStore.clearToken()
    message.error('登录已过期，请重新登录')
    window.location.href = '/login'
  }

  function connect() {
    if (typeof WebSocket === 'undefined') return
    const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://'
    const host = window.location.host
    const token = Cookies.get('token') || ''
    const url = `${protocol}${host}/websocket/pt/status${token ? `?token=${token}` : ''}`

    // unauthorized 是这一条连接自己的状态，不与其他连接共享：换新 token 重连后
    // 会立刻替换掉外层 ws 变量并产生一条新连接（有它自己独立的 unauthorized），
    // 旧连接稍后姗姗来迟的 onclose 只应看它自己的 unauthorized，不能被新连接影响，
    // 否则旧连接的 onclose 会在新连接已经连上之后又多起一次不必要的重连。
    let unauthorized = false

    ws = new WebSocket(url)

    ws.onopen = () => {
      refreshedOnce = false
    }

    ws.onmessage = (event: MessageEvent) => {
      if (event.data === 'unauthorized') {
        unauthorized = true
        handleUnauthorized()
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
      // 鉴权失败导致的关闭由 handleUnauthorized 自行决定重连（换新 token 后）或登出，
      // 不能走下面的定时重连，否则会带着同一个过期 token 无限重连。
      if (unauthorized) return
      reconnectTimer = setTimeout(() => {
        connect()
      }, 3000)
    }

    /**
     * WebSocket 鉴权失败时，先尝试用 refreshToken 静默换新 access token 再重连，
     * 而不是直接清 token 跳登录页——避免 access token 只是正常到了 2 小时期限，
     * 就把还在有效期内（7 天）的登录会话强制打断。
     */
    async function handleUnauthorized() {
      if (refreshedOnce) {
        forceLogout()
        return
      }
      refreshedOnce = true
      try {
        const userStore = useUserStore()
        await userStore.refreshTokenFn()
        connect()
      } catch (e) {
        forceLogout()
      }
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
