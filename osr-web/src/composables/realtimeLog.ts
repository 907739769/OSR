/**
 * 实时日志页（views/monitor/log/realtime.vue）的纯逻辑部分：行结构、过滤、高亮、重连去重、导出。
 * 拆出来是为了能单测——这几件事出错都不报错，只表现为「日志重复了 / 少了 / 高亮不对」。
 */

/** 一条日志行。后端推的是结构化 JSON，级别是解析出来的字段而不是「整行里有没有 ERROR 这几个字母」 */
export interface LogLine {
  /** 前端自增 id，用作 v-for 的 key：截断缓冲区时下标会整体平移，拿下标当 key 等于每次截断全表重渲染 */
  id: number
  ts?: string
  trace?: string
  level: string
  logger?: string
  msg: string
  /** 异常堆栈等续行：没有自己的时间/级别，级别由后端继承自首行 */
  cont?: boolean
  /** 分隔线（历史结束 / 重新连接 / 文件滚动），不是真实日志 */
  divider?: boolean
}

export interface Part { text: string; hit: boolean }

export const LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR'] as const

/**
 * 关键字匹配整行（时间 + traceId + 级别 + logger + 消息）。
 * 把 traceId 也纳进来是有意的：一次请求的全链路日志共用同一个 traceId，
 * 粘一个 traceId 进来就等于把这条请求的所有日志从几千行里拎出来——这是这个框最有用的用法。
 */
export function fullText(line: LogLine): string {
  return `${line.ts ?? ''} ${line.trace ?? ''} ${line.level} ${line.logger ?? ''} ${line.msg}`
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export interface KeywordMatcher {
  test: (line: LogLine) => boolean
  /** 全局标志的高亮正则，每次使用前须重置 lastIndex（splitParts 内部处理） */
  highlight: RegExp
}

/**
 * 关键字 → 匹配器。空关键字返回 null（不过滤）。
 * 正则写到一半必然经过非法状态（如 "(" ），此时返回 undefined：不过滤而不是把整屏清空，
 * 调用方据此把输入框标红。
 */
export function buildMatcher(keyword: string, useRegex: boolean): KeywordMatcher | null | undefined {
  const kw = keyword.trim()
  if (!kw) return null
  let source: string
  if (useRegex) {
    try {
      new RegExp(kw)
    } catch {
      return undefined
    }
    source = kw
  } else {
    source = escapeRegExp(kw)
  }
  const test = new RegExp(source, 'i')
  return {
    test: (line) => test.test(fullText(line)),
    highlight: new RegExp(source, 'gi'),
  }
}

/**
 * 把一段文本切成「命中 / 未命中」片段，供模板逐段渲染。
 * 刻意不用 v-html 做高亮：日志里含有来自网盘的文件名等非可信数据，
 * 拼 HTML 就等于把刚拆掉的 XSS 面又装回去。分段 + 文本插值由 Vue 自动转义。
 */
export function splitParts(text: string, re: RegExp | null | undefined): Part[] {
  if (!re || !text) return [{ text, hit: false }]
  re.lastIndex = 0
  const parts: Part[] = []
  let last = 0
  let m: RegExpExecArray | null
  let guard = 0
  while ((m = re.exec(text)) !== null && guard++ < 200) {
    // 能匹配空串的正则（如 a*）会让 lastIndex 原地不动，必须手动推进，否则死循环
    if (m[0].length === 0) {
      re.lastIndex++
      continue
    }
    if (m.index > last) parts.push({ text: text.slice(last, m.index), hit: false })
    parts.push({ text: m[0], hit: true })
    last = m.index + m[0].length
  }
  if (last < text.length) parts.push({ text: text.slice(last), hit: false })
  return parts.length ? parts : [{ text, hit: false }]
}

/** 未在复选框里列出的级别（TRACE 等）跟随 Info，与后端 LevelFilter 一致 */
export function levelEnabled(level: string, filters: Record<string, boolean>): boolean {
  return level in filters ? filters[level] : filters.INFO
}

/** 传给后端的 levels 参数：全勾选时不传（不过滤），一个都没勾时传 "-"（全部过滤掉） */
export function levelsParam(filters: Record<string, boolean>): string | null {
  const on = LEVELS.filter(l => filters[l])
  if (on.length === LEVELS.length) return null
  return on.length ? on.join(',') : '-'
}

/**
 * 重连时的历史去重。
 *
 * 后端每次建立连接都会先推最后 500 行历史。断线重连（后端重启、网络抖动、token 到期换新）时
 * 这 500 行与屏幕上已有的内容大段重叠——原先不做处理，每重连一次就重复一整段。
 * 判据是时间戳：不晚于断线前最后一条的行丢掉，续行跟随它的首行。同一毫秒内的多条日志
 * 会被一起判为「已有」，这是可接受的代价（要么重一两行、要么漏一两行，选了后者更不扰人）。
 *
 * 另一面：如果历史里<一条都没有>被判为已有，说明断线期间写入的行比 500 行还多，
 * 中间有一段没拿到——这件事要告诉用户（sawOverlap = false）。
 */
export class ResumeFilter {
  private skipping = false
  sawOverlap = false

  constructor(private readonly lastTs: string) {}

  accept(line: Pick<LogLine, 'ts' | 'cont'>): boolean {
    if (line.ts) {
      // 该格式（yyyy-MM-dd HH:mm:ss.SSS）的字典序即时间序
      this.skipping = line.ts <= this.lastTs
      if (this.skipping) this.sawOverlap = true
    }
    return !this.skipping
  }
}

/** 截到最后 max 条（不超过时原样返回同一个数组） */
export function keepLast<T>(arr: T[], max: number): T[] {
  return arr.length > max ? arr.slice(arr.length - max) : arr
}

/** 还原成日志文件里的样子，用于导出 */
export function formatLine(line: LogLine): string {
  if (line.divider) return line.msg
  if (line.ts) return `[${line.ts}][${line.trace ?? ''}][${line.level.padEnd(5)}][${line.logger ?? ''}] ${line.msg}`
  return line.msg
}

/** 后端推来的一个行对象（批量协议的数组元素，或旧协议 t=log 的消息体）→ LogLine */
export function toLogLine(obj: Record<string, unknown>, id: number): LogLine {
  return {
    id,
    ts: obj.ts as string | undefined,
    trace: (obj.trace as string | undefined) ?? '',
    level: (obj.level as string | undefined) || 'INFO',
    logger: (obj.logger as string | undefined) ?? '',
    msg: String(obj.msg ?? ''),
    cont: obj.cont === true,
  }
}
