<template>
  <div class="realtime-log-container">
    <v-card variant="flat" class="log-card">
      <!-- Header toolbar -->
      <div class="log-header">
        <div class="header-left">
          <v-select
            v-model="logType"
            :items="[{ title: 'Info', value: 'info' }, { title: 'Debug', value: 'debug' }, { title: 'Error', value: 'error' }]"
            label="日志类型"
            density="compact"
            variant="outlined"
            hide-details
            style="width: 140px"
            @update:model-value="handleLogTypeChange"
          />
          <v-chip :color="connectionStatus.tagType" size="small" variant="tonal" style="margin-left: 12px">
            <v-icon :icon="connectionStatus.icon" start size="14" /> {{ connectionStatus.text }}
          </v-chip>
        </div>
        <div class="header-right">
          <v-checkbox v-model="autoScroll" density="compact" hide-details label="自动滚动" />
          <v-checkbox v-model="filterDebug" density="compact" hide-details label="Debug" />
          <v-checkbox v-model="filterInfo" density="compact" hide-details label="Info" />
          <v-checkbox v-model="filterWarn" density="compact" hide-details label="Warn" />
          <v-checkbox v-model="filterError" density="compact" hide-details label="Error" />
          <v-btn size="small" variant="outlined" prepend-icon="mdi-delete-outline" @click="clearLog">清屏</v-btn>
          <v-btn size="small" variant="outlined" prepend-icon="mdi-refresh" @click="reconnect">重连</v-btn>
        </div>
      </div>

      <!-- Log content -->
      <div ref="logContentRef" class="log-content" @scroll="handleScroll">
        <div
          v-for="(line, index) in displayLines"
          :key="index"
          class="log-line"
          :class="getLineClass(line)"
        >{{ line.raw }}</div>
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { message } from '@/composables/useMessage'
import { useUserStore } from '@/stores/user'

const logType = ref('info')
const autoScroll = ref(true)
const filterDebug = ref(true)
const filterInfo = ref(true)
const filterWarn = ref(true)
const filterError = ref(true)
const logContentRef = ref<HTMLElement | null>(null)
const logLines = ref<{ raw: string }[]>([])
const isUserScrolled = ref(false)
const connectionState = ref<'disconnected' | 'connecting' | 'connected' | 'closed'>('disconnected')

let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let unauthorized = false

const connectionStatus = computed(() => {
  switch (connectionState.value) {
    case 'connected': return { text: '已连接', icon: 'mdi-check-circle-outline', tagType: 'success' }
    case 'connecting': return { text: '连接中', icon: 'mdi-loading mdi-spin', tagType: 'warning' }
    case 'closed': return { text: '已断开', icon: 'mdi-alert-outline', tagType: 'error' }
    default: return { text: '未连接', icon: 'mdi-alert-outline', tagType: 'error' }
  }
})

// Filter out lines based on level checkboxes
const displayLines = computed(() => {
  if (!filterDebug.value && !filterInfo.value && !filterWarn.value && !filterError.value) return []
  return logLines.value.filter((line) => {
    const raw = line.raw
    if (raw.includes('DEBUG')) return filterDebug.value
    if (raw.includes('ERROR')) return filterError.value
    if (raw.includes('WARN')) return filterWarn.value
    // Default: show INFO and other lines
    return filterInfo.value
  })
})

function getLineClass(line: { raw: string }): string {
  if (line.raw.includes('ERROR')) return 'log-error'
  if (line.raw.includes('WARN')) return 'log-warn'
  if (line.raw.includes('DEBUG')) return 'log-debug'
  return 'log-info'
}

function scrollToBottom() {
  if (autoScroll.value && !isUserScrolled.value && logContentRef.value) {
    logContentRef.value.scrollTop = logContentRef.value.scrollHeight
  }
}

function handleScroll() {
  if (logContentRef.value) {
    const { scrollTop, scrollHeight, clientHeight } = logContentRef.value
    isUserScrolled.value = scrollHeight - scrollTop - clientHeight > 50
  }
}

function clearLog() {
  logLines.value = []
}

function reconnect() {
  disconnect()
  unauthorized = false
  connectWebSocket()
}

function handleLogTypeChange() {
  clearLog()
  reconnect()
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
  connectionState.value = 'disconnected'
}

function connectWebSocket() {
  const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://'
  const host = window.location.host
  const token = document.cookie.match(/token=([^;]+)/)?.[1] || ''
  // WebSocket path - LogWebSocket uses /websocket/log/ (no /api prefix)
  const url = `${protocol}${host}/websocket/log/${logType.value}${token ? `?token=${token}` : ''}`

  if (typeof WebSocket === 'undefined') {
    message.error('浏览器不支持 WebSocket')
    return
  }

  ws = new WebSocket(url)
  connectionState.value = 'connecting'

  ws.onopen = () => {
    isUserScrolled.value = false
    connectionState.value = 'connected'
  }

  ws.onmessage = (event) => {
    const rawLine = event.data

    // 后端鉴权失败（token 无效/过期或缺少权限）时会发这条纯文本控制消息后关闭连接，
    // 不是日志内容，必须单独识别，否则会被当成普通日志行显示，且断线后还会
    // 用同一个失效 token 不断自动重连，陷入死循环刷屏。
    if (rawLine === 'unauthorized') {
      unauthorized = true
      const userStore = useUserStore()
      userStore.clearToken()
      message.error('登录已过期或无权限查看日志，请重新登录')
      window.location.href = '/login'
      return
    }

    // 后端推来的是 <div class='log-item log-info'>...</div> 这样的 HTML 行，
    // 这里剥掉标签只取文本，再套用前端自己的样式。
    //
    // 必须用 DOMParser 而非 `div.innerHTML = rawLine`：后者即便元素未插入文档也会
    // 解析并激活内容，<img src=x onerror=...> 会真的执行（已实测）。日志里含有来自
    // 网盘的文件名等非可信数据，攻击者只需构造一个恶意文件名，就能在管理员打开实时
    // 日志页时于其会话中执行任意脚本。DOMParser 产出的是惰性文档，不加载资源也不执行脚本。
    const doc = new DOMParser().parseFromString(rawLine, 'text/html')
    const textContent = doc.body.textContent || ''

    logLines.value.push({ raw: textContent })

    // Performance: keep max 5000 lines
    if (logLines.value.length > 5000) {
      logLines.value = logLines.value.slice(-4000)
    }

    scrollToBottom()
  }

  ws.onerror = () => {
    message.error('WebSocket 连接错误')
    connectionState.value = 'closed'
  }

  ws.onclose = () => {
    connectionState.value = 'closed'
    // 鉴权失败导致的关闭不自动重连：token 不会自己变得合法，重连只会无限重复失败
    if (unauthorized) return
    // Auto-reconnect after 3 seconds
    reconnectTimer = setTimeout(() => {
      connectWebSocket()
    }, 3000)
  }
}

onMounted(() => {
  connectWebSocket()
})

onUnmounted(() => {
  disconnect()
})
</script>

<style scoped lang="scss">
.realtime-log-container {
  height: calc(100vh - 120px);
  display: flex;
  flex-direction: column;
}

.log-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 4px;
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--osr-border-base);
  background-color: var(--osr-bg-page);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.log-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  background-color: #1e1e1e;
  padding: 12px 16px;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-wrap: break-word;
  word-break: break-all;
}

.log-content::-webkit-scrollbar {
  width: 6px;
}

.log-content::-webkit-scrollbar-thumb {
  background-color: #555;
  border-radius: 3px;
}

.log-line {
  padding: 1px 0;
  color: #d4d4d4;
  white-space: pre-wrap;
  word-wrap: break-word;
}

.log-error {
  color: #f44747 !important;
}

.log-warn {
  color: #cca700 !important;
}

.log-debug {
  color: #6a9955 !important;
}

.log-info {
  color: #d4d4d4 !important;
}

@media (max-width: 768px) {
  .log-header {
    flex-direction: column;
    gap: 8px;
    align-items: flex-start;
  }

  .header-right {
    width: 100%;
    justify-content: flex-start;
  }

  .log-content {
    font-size: 11px;
    padding: 8px;
  }
}
</style>
