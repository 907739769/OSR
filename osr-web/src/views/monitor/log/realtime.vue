<template>
  <div class="realtime-log-container">
    <v-card variant="flat" class="log-card">
      <!-- Header toolbar -->
      <div class="log-header">
        <div class="header-row">
          <v-select
            v-model="logType"
            :items="sourceItems"
            label="日志源"
            density="compact"
            variant="outlined"
            hide-details
            class="source-select"
            @update:model-value="handleLogTypeChange"
          />
          <v-chip :color="connectionStatus.tagType" size="small" variant="tonal" class="status-chip">
            <v-icon :icon="connectionStatus.icon" start size="14" /> {{ connectionStatus.text }}
          </v-chip>
          <v-text-field
            v-model="keyword"
            class="keyword-field"
            :placeholder="useRegex ? '正则过滤，如 (超时|失败)' : '过滤关键字，可搜 traceId / logger / 消息'"
            :error="regexInvalid"
            :messages="regexInvalid ? '正则表达式无效' : undefined"
            density="compact"
            variant="outlined"
            hide-details="auto"
            clearable
            prepend-inner-icon="search"
          >
            <template #append-inner>
              <v-tooltip text="正则模式" location="top">
                <template #activator="{ props: tp }">
                  <v-btn
                    v-bind="tp"
                    icon="regex"
                    size="x-small"
                    density="comfortable"
                    :variant="useRegex ? 'tonal' : 'text'"
                    :color="useRegex ? 'primary' : undefined"
                    @mousedown.prevent
                    @click.stop="useRegex = !useRegex"
                  />
                </template>
              </v-tooltip>
            </template>
          </v-text-field>
          <span class="line-counter">{{ displayLines.length }} / {{ logLines.length }}</span>
        </div>
        <div class="header-row header-row-filters">
          <v-checkbox v-model="levelFilters.DEBUG" density="compact" hide-details label="Debug" />
          <v-checkbox v-model="levelFilters.INFO" density="compact" hide-details label="Info" />
          <v-checkbox v-model="levelFilters.WARN" density="compact" hide-details label="Warn" />
          <v-checkbox v-model="levelFilters.ERROR" density="compact" hide-details label="Error" />
          <v-checkbox v-model="autoScroll" density="compact" hide-details label="自动滚动" />
          <v-spacer class="d-none d-md-block" />
          <v-btn size="small" variant="outlined" prepend-icon="trash-2" @click="clearLog">清屏</v-btn>
          <v-btn size="small" variant="outlined" prepend-icon="refresh-cw" @click="reconnect">重连</v-btn>
        </div>
      </div>

      <!-- Log content -->
      <div ref="logContentRef" class="log-content" @scroll="handleScroll">
        <div
          v-for="(line, index) in displayLines"
          :key="index"
          class="log-line"
          :class="[levelClass(line.level), { 'log-divider': line.divider }]"
        ><template v-if="line.ts"><span class="log-ts">[{{ line.ts }}]</span><span
          class="log-meta"
        >[{{ line.trace }}][{{ line.level }}][{{ line.logger }}]</span></template><span
          v-else-if="line.cont"
          class="log-cont"
        /><span class="log-msg"><template
          v-for="(p, i) in line.parts"
          :key="i"
        ><mark v-if="p.hit" class="log-hit">{{ p.text }}</mark><template v-else>{{ p.text }}</template></template></span></div>
        <div v-if="!displayLines.length" class="log-empty">{{ emptyHint }}</div>
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { message } from '@/composables/useMessage'
import { useUserStore } from '@/stores/user'

/** 一条日志行。后端推的是结构化 JSON，级别是解析出来的字段而不是「整行里有没有 ERROR 这几个字母」 */
interface LogLine {
  ts?: string
  trace?: string
  level: string
  logger?: string
  msg: string
  /** 异常堆栈等续行：没有自己的时间/级别，级别由后端继承自首行 */
  cont?: boolean
  /** 「历史日志结束」那条分隔线，不是真实日志 */
  divider?: boolean
}

interface Part { text: string; hit: boolean }
type DisplayLine = LogLine & { parts: Part[] }

const MAX_LINES = 5000

// 日志源只剩两档，对应 logback 里合并后的两个文件：sys-all.log 与 sys-error.log。
// 早先的 Info/Debug 两档是<互斥>切分的两个文件（sys-debug.log 只含 DEBUG、sys-info.log 含 INFO 及以上），
// 而业务模块跑在 DEBUG、框架跑在 INFO——同一条调用链被劈在两个文件里，选哪个都看不全。
const sourceItems = [
  { title: '全部', value: 'all' },
  { title: '仅错误', value: 'error' },
]

const logType = ref('all')
const autoScroll = ref(true)
const keyword = ref('')
const useRegex = ref(false)
const levelFilters = reactive<Record<string, boolean>>({ DEBUG: true, INFO: true, WARN: true, ERROR: true })
const logContentRef = ref<HTMLElement | null>(null)
const logLines = ref<LogLine[]>([])
const isUserScrolled = ref(false)
const connectionState = ref<'disconnected' | 'connecting' | 'connected' | 'closed'>('disconnected')

let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
// access token 过期属正常现象（2 小时），此时应先尝试用 refreshToken 静默换新再重连；
// 只有换新本身失败（refreshToken 也过期/无效）才是真正需要重新登录的场景。
// 用这个标记避免"刷新成功但服务端仍拒绝"时无限重试；跨连接共享，换新成功后重置。
let refreshedOnce = false

const connectionStatus = computed(() => {
  switch (connectionState.value) {
    case 'connected': return { text: '已连接', icon: 'circle-check', tagType: 'success' }
    case 'connecting': return { text: '连接中', icon: 'loader-circle', tagType: 'warning' }
    case 'closed': return { text: '已断开', icon: 'triangle-alert', tagType: 'error' }
    default: return { text: '未连接', icon: 'triangle-alert', tagType: 'error' }
  }
})

/* ------------------------------------------------------------------ 过滤 */

const compiledRegex = computed<RegExp | null>(() => {
  if (!useRegex.value) return null
  const kw = keyword.value?.trim()
  if (!kw) return null
  try {
    return new RegExp(kw, 'i')
  } catch {
    return null
  }
})

const regexInvalid = computed(() => useRegex.value && !!keyword.value?.trim() && compiledRegex.value === null)

/**
 * 关键字匹配整行（时间 + traceId + 级别 + logger + 消息）。
 * 把 traceId 也纳进来是有意的：一次请求的全链路日志共用同一个 traceId，
 * 粘一个 traceId 进来就等于把这条请求的所有日志从几千行里拎出来——这是这个框最有用的用法。
 */
function fullText(line: LogLine): string {
  return `${line.ts ?? ''} ${line.trace ?? ''} ${line.level} ${line.logger ?? ''} ${line.msg}`
}

const matcher = computed<((s: string) => boolean) | null>(() => {
  const kw = keyword.value?.trim()
  if (!kw) return null
  if (useRegex.value) {
    const re = compiledRegex.value
    // 正则写到一半必然经过非法状态（如 "(" ），此时不过滤而不是把整屏清空
    if (!re) return null
    return (s: string) => re.test(s)
  }
  const lower = kw.toLowerCase()
  return (s: string) => s.toLowerCase().includes(lower)
})

function levelEnabled(level: string): boolean {
  // TRACE 等未在复选框里列出的级别跟随 Info，与改造前「其余一律按 INFO 处理」保持一致
  return level in levelFilters ? levelFilters[level] : levelFilters.INFO
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 把消息切成「命中 / 未命中」两种片段，供模板逐段渲染。
 * 刻意不用 v-html 做高亮：日志里含有来自网盘的文件名等非可信数据，
 * 拼 HTML 就等于把刚拆掉的 XSS 面又装回去。分段 + 文本插值由 Vue 自动转义。
 */
function splitParts(msg: string): Part[] {
  const kw = keyword.value?.trim()
  if (!kw) return [{ text: msg, hit: false }]

  let re: RegExp
  if (useRegex.value) {
    if (!compiledRegex.value) return [{ text: msg, hit: false }]
    re = new RegExp(compiledRegex.value.source, 'gi')
  } else {
    re = new RegExp(escapeRegExp(kw), 'gi')
  }

  const parts: Part[] = []
  let last = 0
  let m: RegExpExecArray | null
  let guard = 0
  while ((m = re.exec(msg)) !== null && guard++ < 200) {
    // 能匹配空串的正则（如 a*）会让 lastIndex 原地不动，必须手动推进，否则死循环
    if (m[0].length === 0) {
      re.lastIndex++
      continue
    }
    if (m.index > last) parts.push({ text: msg.slice(last, m.index), hit: false })
    parts.push({ text: m[0], hit: true })
    last = m.index + m[0].length
  }
  if (last < msg.length) parts.push({ text: msg.slice(last), hit: false })
  return parts.length ? parts : [{ text: msg, hit: false }]
}

const displayLines = computed<DisplayLine[]>(() => {
  const match = matcher.value
  const out: DisplayLine[] = []
  for (const line of logLines.value) {
    if (!line.divider && !levelEnabled(line.level)) continue
    if (match && !line.divider && !match(fullText(line))) continue
    out.push({ ...line, parts: splitParts(line.msg) })
  }
  return out
})

const emptyHint = computed(() => {
  if (!logLines.value.length) return '暂无日志'
  return '当前过滤条件下没有匹配的日志'
})

function levelClass(level: string): string {
  switch (level) {
    case 'ERROR': return 'log-error'
    case 'WARN': return 'log-warn'
    case 'DEBUG': return 'log-debug'
    case 'TRACE': return 'log-debug'
    default: return 'log-info'
  }
}

/* ------------------------------------------------------------------ 滚动 */

function scrollToBottom() {
  if (!autoScroll.value || isUserScrolled.value) return
  nextTick(() => {
    if (logContentRef.value) {
      logContentRef.value.scrollTop = logContentRef.value.scrollHeight
    }
  })
}

function handleScroll() {
  if (logContentRef.value) {
    const { scrollTop, scrollHeight, clientHeight } = logContentRef.value
    isUserScrolled.value = scrollHeight - scrollTop - clientHeight > 50
  }
}

// 过滤条件一变，内容高度会突变，此时应重新贴到底部而不是停在半空
watch([keyword, useRegex, () => ({ ...levelFilters })], () => {
  isUserScrolled.value = false
  scrollToBottom()
})

/* ------------------------------------------------------------------ 连接 */

function clearLog() {
  logLines.value = []
}

function reconnect() {
  disconnect()
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

function pushLine(line: LogLine) {
  logLines.value.push(line)
  if (logLines.value.length > MAX_LINES) {
    logLines.value = logLines.value.slice(-(MAX_LINES - 1000))
  }
  scrollToBottom()
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

  // unauthorized 是这一条连接自己的状态，不与其他连接共享：换新 token 重连后
  // 会立刻替换掉外层 ws 变量并产生一条新连接（有它自己独立的 unauthorized），
  // 旧连接稍后姗姗来迟的 onclose 只应看它自己的 unauthorized，不能被新连接影响，
  // 否则旧连接的 onclose 会在新连接已经连上之后又多起一次不必要的重连。
  let unauthorized = false

  ws = new WebSocket(url)
  connectionState.value = 'connecting'

  ws.onopen = () => {
    isUserScrolled.value = false
    connectionState.value = 'connected'
    refreshedOnce = false
  }

  ws.onmessage = (event) => {
    const raw = event.data as string

    let obj: Record<string, unknown> | null = null
    try {
      const parsed = JSON.parse(raw)
      if (parsed && typeof parsed === 'object') obj = parsed as Record<string, unknown>
    } catch {
      obj = null
    }

    // 旧协议兜底：后端尚未升级时推的是纯文本 "unauthorized" 或 <div>…</div> 包好的 HTML 行。
    // 前后端是两个容器，完全可能只重建了其中一个，这条降级路径很便宜。
    if (!obj) {
      if (raw === 'unauthorized') {
        unauthorized = true
        handleUnauthorized()
        return
      }
      pushLine(parseLegacyHtml(raw))
      return
    }

    switch (obj.t) {
      case 'unauthorized':
        unauthorized = true
        handleUnauthorized()
        return
      case 'history-end':
        pushLine({ level: 'INFO', msg: '--- 历史日志结束 ---', divider: true })
        return
      case 'error':
        pushLine({ level: 'ERROR', msg: String(obj.msg ?? '') })
        return
      default:
        pushLine({
          ts: obj.ts as string | undefined,
          trace: (obj.trace as string | undefined) ?? '',
          level: (obj.level as string | undefined) || 'INFO',
          logger: (obj.logger as string | undefined) ?? '',
          msg: String(obj.msg ?? ''),
          cont: obj.cont === true,
        })
    }
  }

  ws.onerror = () => {
    message.error('WebSocket 连接错误')
    connectionState.value = 'closed'
  }

  ws.onclose = () => {
    connectionState.value = 'closed'
    // 鉴权失败导致的关闭由 handleUnauthorized 自行决定重连（换新 token 后）或登出，
    // 不能走下面的定时重连，否则会带着同一个过期 token 无限重连。
    if (unauthorized) return
    // Auto-reconnect after 3 seconds
    reconnectTimer = setTimeout(() => {
      connectWebSocket()
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
      connectWebSocket()
    } catch (e) {
      forceLogout()
    }
  }
}

/**
 * 旧协议的一行 HTML（<div class='log-item log-info'>…</div>）转成结构化行。
 *
 * 必须用 DOMParser 而非 `div.innerHTML = raw`：后者即便元素未插入文档也会解析并激活内容，
 * <img src=x onerror=...> 会真的执行（已实测）。日志里含有来自网盘的文件名等非可信数据，
 * 攻击者只需构造一个恶意文件名，就能在管理员打开实时日志页时于其会话中执行任意脚本。
 * DOMParser 产出的是惰性文档，不加载资源也不执行脚本。
 */
function parseLegacyHtml(raw: string): LogLine {
  const doc = new DOMParser().parseFromString(raw, 'text/html')
  const text = doc.body.textContent || ''
  const m = text.match(/^\[([\d-]{10} [\d:.]{12})\]\[([^\]]*)\]\[([A-Z ]{1,5})\]\[([^\]]*)\] ?([\s\S]*)$/)
  if (m) {
    return { ts: m[1], trace: m[2], level: m[3].trim(), logger: m[4], msg: m[5] }
  }
  return { level: 'INFO', msg: text }
}

function forceLogout() {
  const userStore = useUserStore()
  userStore.clearToken()
  message.error('登录已过期，请重新登录')
  window.location.href = '/login'
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
  flex-direction: column;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--osr-border-base);
  background-color: var(--osr-bg-page);
  flex-shrink: 0;
}

.header-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.header-row-filters {
  gap: 8px;
}

.source-select {
  flex: 0 0 130px;
  max-width: 130px;
}

.status-chip {
  flex-shrink: 0;
}

.keyword-field {
  flex: 1 1 260px;
  min-width: 200px;
}

.line-counter {
  flex-shrink: 0;
  font-family: var(--osr-font-mono);
  font-size: 12px;
  color: var(--osr-text-secondary);
  font-variant-numeric: tabular-nums;
}

.log-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  background-color: #1e1e1e;
  padding: 12px 16px;
  font-family: var(--osr-font-mono);
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

.log-ts {
  color: #808080;
}

.log-meta {
  color: #6b7d8f;
}

/* 异常堆栈等续行：缩进对齐，让「一次异常」在视觉上是一块而不是一堆平行的行 */
.log-cont::before {
  content: '    ';
  white-space: pre;
}

.log-msg {
  margin-left: 6px;
}

.log-hit {
  background-color: #ffd54f;
  color: #1e1e1e;
  border-radius: 2px;
  padding: 0 1px;
}

.log-divider {
  color: #888 !important;
  border-bottom: 1px dashed #555;
  margin: 8px 0;
}

.log-empty {
  color: #808080;
  padding: 24px 0;
  text-align: center;
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
  .realtime-log-container {
    /* 移动端可用区 = 100dvh - appbar(50) - tabbar(56) - safe-area - 页面 padding，
       留 20px 余量防止底部被固定 tabbar 遮挡导致 autoScroll 看不到最新日志 */
    height: calc(100dvh - 140px - env(safe-area-inset-bottom, 0px));
  }

  .header-row {
    gap: 8px;
    row-gap: 4px;
  }

  .header-row-filters {
    gap: 4px 12px;
  }

  .header-row-filters :deep(.v-label) {
    font-size: 12px;
  }

  .source-select {
    flex: 0 0 110px;
    max-width: 110px;
  }

  .keyword-field {
    flex: 1 1 100%;
  }

  .log-content {
    font-size: 11px;
    padding: 8px;
  }

  /* 窄屏隐藏 traceId/级别/logger 那一段元数据，只留时间和消息。
     改造前这是后端往 HTML 里塞 hidden-xs 类实现的（还得让前端知道那个 class）；
     现在字段是分开推的，纯 CSS 就够了。 */
  .log-meta {
    display: none;
  }
}
</style>
