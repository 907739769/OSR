import { ref, computed, onMounted, onUnmounted } from 'vue'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import { getPtCalendarApi, type CalendarEntry } from '@/api/openlist/ptCalendar'

// dayjs 在本项目里只有日历用到，locale 就在这里设。zh-cn 同时带来两件必要的事：
// 中文星期名，以及 weekStart=1（周一起始）——中文语境的月历没有从周日排起的
dayjs.locale('zh-cn')

/** 月历表头，与 zh-cn 的 weekStart=1 对齐 */
export const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日']

/** 集状态 -> 展示用的文案与配色，PC 与移动端共用一套口径 */
export const EPISODE_STATE_META: Record<string, { label: string; color: string }> = {
  IN_LIBRARY: { label: '已入库', color: 'success' },
  IN_FLIGHT: { label: '下载中', color: 'info' },
  UPGRADING: { label: '洗版中', color: 'warning' },
  BLOCKED: { label: '已阻塞', color: 'error' },
  MISSING: { label: '缺失', color: 'default' }
}

export function stateMeta(state: string) {
  return EPISODE_STATE_META[state] || { label: state || '未知', color: 'default' }
}

/** TMDb 海报。w92 够日历里的小图用，别拉 w200 白耗流量 */
export function posterUrl(path: string | null) {
  return path ? `https://image.tmdb.org/t/p/w92${path}` : ''
}

/**
 * 追剧日历共享逻辑：按自然月取数，PC 渲染成月历网格，移动端渲染成按日分组的清单。
 * 两端共用同一个月份窗口，翻页/回今天的行为完全一致。
 */
export function usePtCalendar() {
  const loading = ref(false)
  /**
   * 这次加载是否失败了。
   * <p>
   * 不能只靠「结果为空」表达失败：空结果渲染出来是「本月没有排播」——接口挂了和这个月
   * 确实没有更新长得一模一样，而用户对「某个月没排播」本来就没有预期，看到空日历只会
   * 当成真的。
   * </p>
   */
  const loadFailed = ref(false)
  /** 当前月份锚点，恒为该月 1 号 */
  const anchor = ref(dayjs().startOf('month'))
  const entries = ref<CalendarEntry[]>([])
  /** 状态筛选；空串=全部。图例本来就是这套颜色的说明，顺手让它可点 */
  const activeState = ref('')

  const monthLabel = computed(() => anchor.value.format('YYYY 年 M 月'))

  /**
   * 「今天」是个会变的量，不能在建 composable 时算一次就完。
   * 日历这类看板页很容易被开着过夜，跨过零点后高亮框会停在昨天。
   */
  const today = ref(dayjs().format('YYYY-MM-DD'))
  const refreshToday = () => {
    const now = dayjs().format('YYYY-MM-DD')
    if (now !== today.value) today.value = now
  }
  let todayTimer: ReturnType<typeof setInterval> | undefined
  onMounted(() => {
    // 一分钟一次足够：这个值只驱动一个高亮框，早一分钟晚一分钟无所谓，
    // 但整点前后必须自己变过来
    todayTimer = setInterval(refreshToday, 60_000)
  })
  onUnmounted(() => { if (todayTimer) clearInterval(todayTimer) })

  /**
   * 只采信最后一次请求的结果。
   * <p>
   * 翻月是「立刻改 anchor + 发一个请求」，连点几次就有多个请求在飞，而哪个后到就用哪个的
   * 数据——页面标题已经是 3 月、格子里铺的却是 2 月的排播，且没有任何异常迹象。
   * </p>
   */
  let requestId = 0

  const load = async () => {
    const current = ++requestId
    loading.value = true
    try {
      // 取整个网格覆盖的范围而不是只取本月：月历首尾会露出上月末和下月初几天，
      // 只查本月的话那几格永远是空的，看起来像"那几天没有更新"
      const start = anchor.value.startOf('month').startOf('week')
      const end = anchor.value.endOf('month').endOf('week')
      const data = await getPtCalendarApi(start.format('YYYY-MM-DD'), end.format('YYYY-MM-DD')) || []
      if (current !== requestId) return
      entries.value = data
      loadFailed.value = false
    } catch (e) {
      console.error(e)
      if (current !== requestId) return
      entries.value = []
      loadFailed.value = true
    } finally {
      // 只有最后一次请求负责收掉 loading，否则先返回的那个会把还在飞的那次也标成完成
      if (current === requestId) loading.value = false
    }
  }

  const goPrevMonth = () => { anchor.value = anchor.value.subtract(1, 'month'); load() }
  const goNextMonth = () => { anchor.value = anchor.value.add(1, 'month'); load() }

  /**
   * 回到本月。已经在本月时也要把 anchor 重新赋值一次——移动端靠 monthLabel 变化触发
   * 「滚回今天」，不赋值的话用户在本月往下划走后点这个按钮会毫无反应，
   * 而那恰恰是它最常见的用法。
   */
  const goToday = () => {
    refreshToday()
    anchor.value = dayjs().startOf('month')
    load()
    return today.value
  }

  /**
   * 跳到指定月份（yyyy-MM）。只能一格格翻的话，看三个月前要点三次、看去年要点十二次。
   * <p>
   * 必须先用正则卡格式，<b>不能只靠 dayjs 的 isValid()</b>：它对输入相当宽容，
   * 「不是月份-01」这种串会被它解析成 2001-01-01 并判为有效，于是一次误输入会把用户
   * 静默带到 2001 年。
   * </p>
   */
  const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/
  const goMonth = (value: string) => {
    if (!value || !MONTH_PATTERN.test(value)) return
    const next = dayjs(`${value}-01`)
    if (!next.isValid()) return
    anchor.value = next.startOf('month')
    load()
  }

  /** 经过状态筛选后的条目，下面所有派生量都基于它 */
  const visibleEntries = computed(() =>
    activeState.value ? entries.value.filter((e) => e.state === activeState.value) : entries.value
  )

  /** 各状态的条目数，用于图例上的角标；恒按全量算，否则筛选后其余状态会显示成 0 */
  const stateCounts = computed(() => {
    const counts: Record<string, number> = {}
    for (const entry of entries.value) {
      counts[entry.state] = (counts[entry.state] || 0) + 1
    }
    return counts
  })

  const setState = (state: string) => {
    activeState.value = activeState.value === state ? '' : state
  }

  /** 日期 -> 当天的集，供两端按格子/按分组取用 */
  const entriesByDate = computed<Record<string, CalendarEntry[]>>(() => {
    const map: Record<string, CalendarEntry[]> = {}
    for (const entry of visibleEntries.value) {
      (map[entry.airDate] ||= []).push(entry)
    }
    return map
  })

  const inAnchorMonth = (key: string) => {
    const date = dayjs(key)
    return date.month() === anchor.value.month() && date.year() === anchor.value.year()
  }

  /**
   * 本月（不含网格首尾溢出的上下月几天）到底有没有排播。
   * <p>
   * PC 端此前用 entries.length 判空，而它含着溢出天——「本月没有、上月末有」时 PC 不显示
   * 空态、本月格子却全是空的，移动端说「本月没有排播」，同一个月两端结论相反。
   * </p>
   */
  const hasEntriesInMonth = computed(() =>
    visibleEntries.value.some((entry) => inAnchorMonth(entry.airDate))
  )

  /**
   * PC 月历网格：固定 6 行 7 列。
   * 固定 6 行而不是按需 4~6 行，是为了让翻月时网格高度不跳。
   */
  const weeks = computed(() => {
    const first = anchor.value.startOf('month').startOf('week')
    const rows: Array<Array<{ key: string; day: number; inMonth: boolean; isToday: boolean }>> = []
    for (let w = 0; w < 6; w++) {
      const row = []
      for (let d = 0; d < 7; d++) {
        const date = first.add(w * 7 + d, 'day')
        const key = date.format('YYYY-MM-DD')
        row.push({
          key,
          day: date.date(),
          inMonth: date.month() === anchor.value.month(),
          isToday: key === today.value
        })
      }
      rows.push(row)
    }
    return rows
  })

  /**
   * 移动端清单：只列有排播的日期，按日期升序。
   * 月历网格在手机上一格塞不下剧名，改成清单才读得出内容。
   */
  const agenda = computed(() => Object.keys(entriesByDate.value)
    // 网格为了补首尾会多取上下月的几天，清单里不需要它们
    .filter(inAnchorMonth)
    .sort()
    .map((key) => ({
      key,
      label: dayjs(key).format('M月D日 ddd'),
      isToday: key === today.value,
      items: entriesByDate.value[key]
    })))

  load()

  return {
    loading, loadFailed, entries, anchor, monthLabel, today,
    activeState, stateCounts, setState, visibleEntries, hasEntriesInMonth,
    load, goPrevMonth, goNextMonth, goToday, goMonth,
    entriesByDate, weeks, agenda
  }
}
