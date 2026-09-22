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
            @keydown.enter="historyMode && runSearch()"
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
          <span class="line-counter">{{ historyMode ? `${shown.length} 行` : `${view.length} / ${lines.length}` }}</span>
        </div>
        <div class="header-row header-row-filters">
          <v-checkbox v-model="levelFilters.DEBUG" density="compact" hide-details label="Debug" />
          <v-checkbox v-model="levelFilters.INFO" density="compact" hide-details label="Info" />
          <v-checkbox v-model="levelFilters.WARN" density="compact" hide-details label="Warn" />
          <v-checkbox v-model="levelFilters.ERROR" density="compact" hide-details label="Error" />
          <v-spacer class="d-none d-md-block" />
          <v-btn
            size="small"
            :variant="historyMode ? 'tonal' : 'outlined'"
            :color="historyMode ? 'primary' : undefined"
            prepend-icon="history"
            @click="toggleHistoryMode"
          >检索历史</v-btn>
          <v-btn
            v-if="!historyMode"
            size="small"
            :variant="paused ? 'tonal' : 'outlined'"
            :color="paused ? 'warning' : undefined"
            :prepend-icon="paused ? 'play' : 'pause'"
            @click="togglePause"
          >{{ paused ? '继续' : '暂停' }}</v-btn>
          <v-btn size="small" variant="outlined" prepend-icon="download" :disabled="!shown.length" @click="exportLog">导出</v-btn>
          <template v-if="!historyMode">
            <v-btn size="small" variant="outlined" prepend-icon="trash-2" @click="clearLog">清屏</v-btn>
            <v-btn size="small" variant="outlined" prepend-icon="refresh-cw" @click="manualReconnect">重连</v-btn>
          </template>
        </div>
        <!--
          检索历史：在日志文件（含已滚动的分片）里查，复用上面的关键字、正则、级别与日志源。
          实时日志只有最近 500 行历史，而排查的起点常是「通知里的 traceId / 时间点，那是半小时前的事」。
        -->
        <div v-if="historyMode" class="header-row history-row">
          <v-text-field
            v-model="searchFrom"
            type="datetime-local"
            label="开始时间"
            density="compact"
            variant="outlined"
            hide-details
            class="time-field"
          />
          <v-text-field
            v-model="searchTo"
            type="datetime-local"
            label="结束时间"
            density="compact"
            variant="outlined"
            hide-details
            class="time-field"
          />
          <v-btn
            size="small"
            color="primary"
            variant="flat"
            prepend-icon="file-search"
            :loading="searching"
            :disabled="regexInvalid"
            @click="runSearch"
          >检索</v-btn>
          <v-btn size="small" variant="outlined" prepend-icon="arrow-left" @click="toggleHistoryMode">返回实时</v-btn>
          <span v-if="searchSummary" class="search-summary">
            命中 {{ searchSummary.lines.length }} 行 · 扫描 {{ searchSummary.scannedFiles }} 个文件 /
            {{ formatBytes(searchSummary.scannedBytes) }} · 用时 {{ (searchSummary.elapsedMs / 1000).toFixed(1) }}s
            <span v-if="searchSummary.truncated" class="search-truncated">（{{ searchSummary.truncatedReason }}）</span>
          </span>
        </div>
      </div>

      <!-- Log content -->
      <div class="log-body">
        <div ref="logContentRef" class="log-content" @scroll="handleScroll">
          <!--
            v-memo：一行渲染出来之后内容就不会再变，只有高亮条件变了才需要重画。
            没有它的话每来一批新行，Vue 都要把已有的几千行重新生成一遍 vnode 再逐个比对。
          -->
          <div
            v-for="line in shown"
            :key="line.id"
            v-memo="[line, highlightRe]"
            class="log-line"
            :class="[levelClass(line.level), { 'log-divider': line.divider }]"
          ><template v-if="line.ts"><span class="log-ts">[{{ line.ts }}]</span><span class="log-meta">[<span
            v-if="line.trace"
            class="log-trace"
            title="点击只看这次调用链，再点一次取消"
            @click="toggleTrace(line.trace)"
          ><template v-for="(p, i) in splitParts(line.trace, highlightRe)" :key="i"><mark v-if="p.hit" class="log-hit">{{ p.text }}</mark><template v-else>{{ p.text }}</template></template></span>][{{ line.level }}][<template
            v-for="(p, i) in splitParts(line.logger ?? '', highlightRe)"
            :key="i"
          ><mark v-if="p.hit" class="log-hit">{{ p.text }}</mark><template v-else>{{ p.text }}</template></template>]</span></template><span
            v-else-if="line.cont"
            class="log-cont"
          /><span class="log-msg"><template
            v-for="(p, i) in splitParts(line.msg, line.divider ? null : highlightRe)"
            :key="i"
          ><mark v-if="p.hit" class="log-hit">{{ p.text }}</mark><template v-else>{{ p.text }}</template></template></span></div>
          <div v-if="!shown.length" class="log-empty">{{ emptyHint }}</div>
        </div>

        <v-btn
          v-if="!historyMode && (!atBottom || (paused && heldCount))"
          class="jump-bottom"
          size="small"
          color="primary"
          variant="flat"
          prepend-icon="arrow-down-to-line"
          @click="jumpToBottom"
        >{{ jumpLabel }}</v-btn>
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, reactive, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { message } from '@/composables/useMessage'
import { useUserStore } from '@/stores/user'
import {
  type LogLine,
  buildMatcher,
  splitParts,
  levelEnabled,
  levelsParam,
  ResumeFilter,
  keepLast,
  formatLine,
  toLogLine,
} from '@/composables/realtimeLog'
import { searchLogApi, type LogSearchResult } from '@/api/monitor/log'

/** 缓冲区上限。暂停/上翻期间攒下的新行另有同样的上限 */
const MAX_LINES = 5000
/** 收到的行攒这么久再一次性提交给视图：DEBUG 洪水时每秒几百行，逐行提交就是逐行重渲染 */
const FLUSH_MS = 100
const RECONNECT_BASE_MS = 3000
const RECONNECT_MAX_MS = 30000

// 日志源只剩两档，对应 logback 里合并后的两个文件：sys-all.log 与 sys-error.log。
// 早先的 Info/Debug 两档是<互斥>切分的两个文件（sys-debug.log 只含 DEBUG、sys-info.log 含 INFO 及以上），
// 而业务模块跑在 DEBUG、框架跑在 INFO——同一条调用链被劈在两个文件里，选哪个都看不全。
// 「访问日志」单列一档：它由独立 logger 写进 sys-access.log（logback 里 additivity=false），
// 不进 sys-all.log。分出去是为了让全量日志只剩业务内容，但页面上必须还看得到，
// 否则就不是降噪而是功能退化。
const sourceItems = [
  { title: '全部', value: 'all' },
  { title: '仅错误', value: 'error' },
  { title: '访问日志', value: 'access' },
]

const logType = ref('all')
const keyword = ref('')
/** 过滤实际使用的关键字：输入防抖 200ms，每敲一个字就对 5000 行跑一遍正则没有必要 */
const appliedKeyword = ref('')
const useRegex = ref(false)
const levelFilters = reactive<Record<string, boolean>>({ DEBUG: true, INFO: true, WARN: true, ERROR: true })
const paused = ref(false)
const atBottom = ref(true)
const logContentRef = ref<HTMLElement | null>(null)

/** 已提交的缓冲区（≤ MAX_LINES），id 递增 */
const lines = shallowRef<LogLine[]>([])
/** 当前过滤条件下的可见行，随提交增量追加，过滤条件变化时整体重算 */
const view = shallowRef<LogLine[]>([])
/** 暂停或上翻期间到达的行：先不进视图，否则顶部截断会让正在看的内容跳走 */
let held: LogLine[] = []
const heldCount = ref(0)
/** 尚未提交的行，由定时器批量提交 */
let incoming: LogLine[] = []
let flushTimer: ReturnType<typeof setTimeout> | null = null
let nextId = 1

/** 检索历史模式：视图换成检索结果，实时日志在后台照常接收（进 held），返回实时时一次放出 */
const historyMode = ref(false)
const searchFrom = ref('')
const searchTo = ref('')
const searching = ref(false)
const searchLines = shallowRef<LogLine[]>([])
const searchSummary = ref<LogSearchResult | null>(null)
const shown = computed(() => (historyMode.value ? searchLines.value : view.value))

const connectionState = ref<'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'stopped'>('disconnected')
const reconnectInSec = ref(0)

let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempt = 0
/** 最后一条带时间戳的日志，重连时据此把历史里已经有的部分去掉 */
let lastTs: string | undefined
/** 当前连接的历史阶段（history-end 之前）是否需要去重 */
let resume: ResumeFilter | null = null
// access token 过期属正常现象（2 小时），此时应先尝试用 refreshToken 静默换新再重连；
// 只有换新本身失败（refreshToken 也过期/无效）才是真正需要重新登录的场景。
// 用这个标记避免"刷新成功但服务端仍拒绝"时无限重试；跨连接共享，换新成功后重置。
let refreshedOnce = false

const connectionStatus = computed(() => {
  switch (connectionState.value) {
    case 'connected': return { text: '已连接', icon: 'circle-check', tagType: 'success' }
    case 'connecting': return { text: '连接中', icon: 'loader-circle', tagType: 'warning' }
    case 'reconnecting': return { text: `已断开，${reconnectInSec.value} 秒后重连`, icon: 'loader-circle', tagType: 'warning' }
    case 'stopped': return { text: '已停止', icon: 'triangle-alert', tagType: 'error' }
    default: return { text: '未连接', icon: 'triangle-alert', tagType: 'error' }
  }
})

const jumpLabel = computed(() => {
  const n = heldCount.value
  if (paused.value) return n ? `已暂停 · ${n} 条新日志` : '回到底部'
  return n ? `${n} 条新日志` : '回到底部'
})

/* ------------------------------------------------------------------ 过滤 */

const matcher = computed(() => buildMatcher(appliedKeyword.value ?? '', useRegex.value))
const regexInvalid = computed(() => useRegex.value && !!keyword.value?.trim() && buildMatcher(keyword.value, true) === undefined)
const highlightRe = computed(() => matcher.value?.highlight ?? null)

function passes(line: LogLine): boolean {
  if (line.divider) return true
  if (!levelEnabled(line.level, levelFilters)) return false
  const m = matcher.value
  return !m || m.test(line)
}

function refilter() {
  view.value = lines.value.filter(passes)
}

const emptyHint = computed(() => {
  if (historyMode.value) {
    if (searching.value) return '检索中…'
    return searchSummary.value ? '没有匹配的日志' : '输入关键字、选择时间范围后点「检索」，会在全部日志文件（含已滚动的分片）里查找'
  }
  if (!lines.value.length) return '暂无日志'
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

let keywordTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, (kw) => {
  if (keywordTimer) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => { appliedKeyword.value = kw ?? '' }, 200)
})

// 过滤条件一变，内容高度会突变，此时应重新贴到底部而不是停在半空
watch([appliedKeyword, useRegex], () => {
  refilter()
  if (!historyMode.value) jumpToBottom()
})

/**
 * 级别：先在本地立即筛一遍（点下去马上有反应），再防抖重连，让后端按新级别过滤、
 * 并按新级别重推历史——只看 WARN/ERROR 时，首屏 500 行覆盖的时间窗口会宽得多，
 * 取消勾选 Debug 之后那些行也不再白白传输。
 */
let levelTimer: ReturnType<typeof setTimeout> | null = null
watch(() => ({ ...levelFilters }), () => {
  refilter()
  if (historyMode.value) {
    if (searchSummary.value) runSearch()
  } else {
    jumpToBottom()
  }
  if (levelTimer) clearTimeout(levelTimer)
  levelTimer = setTimeout(() => connect(true), 500)
})

/** 点 traceId：只看这次调用链；再点一次取消 */
function toggleTrace(trace?: string) {
  if (!trace) return
  const next = keyword.value === trace ? '' : trace
  useRegex.value = false
  keyword.value = next
  appliedKeyword.value = next
  // 检索历史里点 traceId：直接按它重新检索，跨文件拿到这次调用的全链路
  if (historyMode.value && next) runSearch()
}

async function runSearch() {
  if (regexInvalid.value || searching.value) return
  searching.value = true
  try {
    const kw = keyword.value?.trim() ?? ''
    const r = await searchLogApi({
      type: logType.value,
      keyword: kw || undefined,
      regex: useRegex.value,
      levels: levelsParam(levelFilters) ?? undefined,
      from: searchFrom.value || undefined,
      to: searchTo.value || undefined,
    })
    // 高亮跟随这次检索实际用的关键字，不等输入防抖
    appliedKeyword.value = kw
    searchLines.value = r.lines.map(o => toLogLine(o, nextId++))
    searchSummary.value = r
    scrollToBottom(true)
  } catch {
    // 请求拦截器已经提示过错误
  } finally {
    searching.value = false
  }
}

function toggleHistoryMode() {
  historyMode.value = !historyMode.value
  if (!historyMode.value) {
    // 回到实时：检索期间攒下的新行一次放出
    jumpToBottom()
  }
}

function formatBytes(n: number): string {
  if (n >= 1 << 20) return `${(n / (1 << 20)).toFixed(1)} MB`
  return `${Math.max(1, Math.round(n / 1024))} KB`
}

/* ------------------------------------------------------------------ 缓冲与提交 */

function receive(line: LogLine) {
  if (resume && !line.divider && !resume.accept(line)) return
  if (line.ts) lastTs = line.ts
  incoming.push(line)
  if (!flushTimer) flushTimer = setTimeout(flush, FLUSH_MS)
}

function divider(msg: string) {
  receive({ id: nextId++, level: 'INFO', msg, divider: true })
}

function flush() {
  flushTimer = null
  if (!incoming.length) return
  const batch = incoming
  incoming = []
  if (paused.value || !atBottom.value || historyMode.value) {
    held = keepLast(held.concat(batch), MAX_LINES)
    heldCount.value = held.length
    return
  }
  commit(batch)
}

function commit(batch: LogLine[]) {
  if (!batch.length) return
  const all = keepLast(lines.value.concat(batch), MAX_LINES)
  lines.value = all
  let v = view.value.concat(batch.filter(passes))
  // 缓冲区顶部被截掉的行，视图里也要去掉；id 递增，从头数到第一个还在的即可
  const firstId = all[0].id
  let k = 0
  while (k < v.length && v[k].id < firstId) k++
  if (k) v = v.slice(k)
  view.value = v
  scrollToBottom()
}

function releaseHeld() {
  const h = held
  held = []
  heldCount.value = 0
  commit(h)
}

/* ------------------------------------------------------------------ 滚动 */

/** 检索历史模式下实时行不进视图，也就不该去动滚动条；force 给检索结果自己用 */
function scrollToBottom(force = false) {
  if (historyMode.value && !force) return
  nextTick(() => {
    const el = logContentRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function handleScroll() {
  const el = logContentRef.value
  if (!el || historyMode.value) return
  const bottom = el.scrollHeight - el.scrollTop - el.clientHeight <= 50
  if (bottom === atBottom.value) return
  atBottom.value = bottom
  // 用户自己滚回了底部：把上翻期间攒下的行放出来
  if (bottom && !paused.value && held.length) releaseHeld()
}

function jumpToBottom() {
  atBottom.value = true
  if (!paused.value && held.length) releaseHeld()
  scrollToBottom()
}

function togglePause() {
  paused.value = !paused.value
  if (!paused.value && atBottom.value && held.length) releaseHeld()
}

/* ------------------------------------------------------------------ 操作 */

function resetBuffer() {
  if (flushTimer) {
    clearTimeout(flushTimer)
    flushTimer = null
  }
  incoming = []
  held = []
  heldCount.value = 0
  lines.value = []
  view.value = []
  lastTs = undefined
  atBottom.value = true
}

function clearLog() {
  // 只清屏，不动 lastTs：之后重连时历史里清屏前的那部分照样不再推回来
  const keepTs = lastTs
  resetBuffer()
  lastTs = keepTs
}

function exportLog() {
  const text = shown.value.map(formatLine).join('\n') + '\n'
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  // 本地时间：toISOString 是 UTC，东八区下文件名会比日志里的时间早 8 小时
  const d = new Date()
  const p2 = (n: number) => String(n).padStart(2, '0')
  const stamp = `${d.getFullYear()}${p2(d.getMonth() + 1)}${p2(d.getDate())}-${p2(d.getHours())}${p2(d.getMinutes())}${p2(d.getSeconds())}`
  a.href = url
  a.download = `osr-${logType.value}${historyMode.value ? '-search' : ''}-${stamp}.log`
  a.click()
  // 立即 revoke 会让部分浏览器（Firefox）在下载真正开始前就失去数据源
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function handleLogTypeChange() {
  connect(true)
  if (historyMode.value && searchSummary.value) runSearch()
}

function manualReconnect() {
  reconnectAttempt = 0
  connect(false)
}

/* ------------------------------------------------------------------ 连接 */

function disconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (ws) {
    ws.onclose = null
    ws.onmessage = null
    ws.onerror = null
    ws.close()
    ws = null
  }
  connectionState.value = 'disconnected'
}

function scheduleReconnect() {
  const delay = Math.min(RECONNECT_BASE_MS * 2 ** reconnectAttempt, RECONNECT_MAX_MS)
  reconnectAttempt++
  reconnectInSec.value = Math.round(delay / 1000)
  connectionState.value = 'reconnecting'
  reconnectTimer = setTimeout(() => connect(false), delay)
}

/**
 * @param fresh true：换了日志源 / 级别，丢掉现有内容从头来；false：断线重连，保留现有内容，
 *              历史里已经有的部分由 ResumeFilter 去掉
 */
function connect(fresh: boolean) {
  disconnect()
  if (typeof WebSocket === 'undefined') {
    message.error('浏览器不支持 WebSocket')
    return
  }
  if (fresh) resetBuffer()
  resume = lastTs ? new ResumeFilter(lastTs) : null
  const isResume = resume !== null

  const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://'
  const token = document.cookie.match(/token=([^;]+)/)?.[1] || ''
  const params = new URLSearchParams()
  if (token) params.set('token', token)
  // v=2：后端按批推送；旧后端忽略这个参数、仍一行一帧，下面两种都认
  params.set('v', '2')
  const levels = levelsParam(levelFilters)
  if (levels) params.set('levels', levels)
  // WebSocket path - LogWebSocket uses /websocket/log/ (no /api prefix)
  const url = `${protocol}${window.location.host}/websocket/log/${logType.value}?${params.toString()}`

  // 这几个状态属于这一条连接自己，不与其他连接共享：换新 token 重连后会立刻替换掉外层 ws 变量
  // 并产生一条新连接，旧连接稍后姗姗来迟的 onclose 只应看它自己的状态。
  let unauthorized = false
  let fatal = false

  const socket = new WebSocket(url)
  ws = socket
  connectionState.value = 'connecting'

  socket.onopen = () => {
    connectionState.value = 'connected'
    refreshedOnce = false
  }

  socket.onmessage = (event) => {
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
      receive(parseLegacyHtml(raw))
      return
    }

    switch (obj.t) {
      case 'unauthorized':
        unauthorized = true
        handleUnauthorized()
        return
      case 'batch':
        for (const o of (obj.lines as Record<string, unknown>[] | undefined) ?? []) {
          receive(toLogLine(o, nextId++))
        }
        return
      case 'history-end': {
        const r = resume
        resume = null
        reconnectAttempt = 0
        if (!isResume) divider('--- 历史日志结束 ---')
        else if (r && !r.sawOverlap) divider('--- 已重新连接：断线期间的日志超过历史回推的行数，中间可能有缺失 ---')
        else divider('--- 已重新连接 ---')
        return
      }
      case 'rotated':
        divider('--- 日志文件已滚动 ---')
        return
      case 'error':
        // 文件不存在、读取失败：后端发完就关，重连也还是同样的结果，停下来等用户手动重连
        fatal = true
        receive({ id: nextId++, level: 'ERROR', msg: String(obj.msg ?? '') })
        return
      default:
        receive(toLogLine(obj, nextId++))
    }
  }

  // 不弹 toast：onerror 之后必然跟着 onclose，状态 chip 已经说明了一切；
  // 原先每次重连失败都弹一次，后端重启那一两分钟里每 3 秒一条
  socket.onerror = () => {}

  socket.onclose = () => {
    if (ws === socket) ws = null
    // 鉴权失败导致的关闭由 handleUnauthorized 自行决定重连（换新 token 后）或登出，
    // 不能走下面的定时重连，否则会带着同一个过期 token 无限重连。
    if (unauthorized) return
    if (fatal) {
      connectionState.value = 'stopped'
      return
    }
    scheduleReconnect()
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
      connect(false)
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
    return { id: nextId++, ts: m[1], trace: m[2], level: m[3].trim(), logger: m[4], msg: m[5] }
  }
  return { id: nextId++, level: 'INFO', msg: text }
}

function forceLogout() {
  const userStore = useUserStore()
  userStore.clearToken()
  message.error('登录已过期，请重新登录')
  window.location.href = '/login'
}

onMounted(() => {
  connect(true)
})

onUnmounted(() => {
  disconnect()
  if (flushTimer) clearTimeout(flushTimer)
  if (keywordTimer) clearTimeout(keywordTimer)
  if (levelTimer) clearTimeout(levelTimer)
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

.history-row {
  gap: 8px;
}

.time-field {
  flex: 0 0 210px;
  max-width: 210px;
}

.search-summary {
  font-size: 12px;
  color: var(--osr-text-secondary);
  font-variant-numeric: tabular-nums;
}

.search-truncated {
  color: rgb(var(--v-theme-warning));
}

.line-counter {
  flex-shrink: 0;
  font-family: var(--osr-font-mono);
  font-size: 12px;
  color: var(--osr-text-secondary);
  font-variant-numeric: tabular-nums;
}

/* 「回到底部」按钮相对它定位，不能挂在滚动容器本身上（会跟着内容滚走） */
.log-body {
  flex: 1;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
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

.jump-bottom {
  position: absolute;
  right: 20px;
  bottom: 16px;
  text-transform: none;
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

.log-trace {
  cursor: pointer;
  text-decoration: underline dotted;
  text-underline-offset: 3px;

  &:hover {
    color: #9cdcfe;
  }
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
       留 20px 余量防止底部被固定 tabbar 遮挡导致看不到最新日志 */
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

  .time-field {
    flex: 1 1 calc(50% - 4px);
    max-width: none;
  }

  .log-content {
    font-size: 11px;
    padding: 8px;
  }

  .jump-bottom {
    right: 12px;
    bottom: 12px;
  }

  /* 窄屏隐藏 traceId/级别/logger 那一段元数据，只留时间和消息。
     改造前这是后端往 HTML 里塞 hidden-xs 类实现的（还得让前端知道那个 class）；
     现在字段是分开推的，纯 CSS 就够了。 */
  .log-meta {
    display: none;
  }
}
</style>
