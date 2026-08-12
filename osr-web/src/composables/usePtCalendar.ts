import { ref, computed } from 'vue'
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
  /** 当前月份锚点，恒为该月 1 号 */
  const anchor = ref(dayjs().startOf('month'))
  const entries = ref<CalendarEntry[]>([])

  const monthLabel = computed(() => anchor.value.format('YYYY 年 M 月'))
  const todayKey = dayjs().format('YYYY-MM-DD')

  const load = async () => {
    loading.value = true
    try {
      // 取整个网格覆盖的范围而不是只取本月：月历首尾会露出上月末和下月初几天，
      // 只查本月的话那几格永远是空的，看起来像"那几天没有更新"
      const start = anchor.value.startOf('month').startOf('week')
      const end = anchor.value.endOf('month').endOf('week')
      entries.value = await getPtCalendarApi(start.format('YYYY-MM-DD'), end.format('YYYY-MM-DD')) || []
    } catch (e) {
      console.error(e)
      entries.value = []
    } finally {
      loading.value = false
    }
  }

  const goPrevMonth = () => { anchor.value = anchor.value.subtract(1, 'month'); load() }
  const goNextMonth = () => { anchor.value = anchor.value.add(1, 'month'); load() }
  const goToday = () => { anchor.value = dayjs().startOf('month'); load() }

  /** 日期 -> 当天的集，供两端按格子/按分组取用 */
  const entriesByDate = computed<Record<string, CalendarEntry[]>>(() => {
    const map: Record<string, CalendarEntry[]> = {}
    for (const entry of entries.value) {
      (map[entry.airDate] ||= []).push(entry)
    }
    return map
  })

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
          isToday: key === todayKey
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
    .filter((key) => {
      // 网格为了补首尾会多取上下月的几天，清单里不需要它们
      const date = dayjs(key)
      return date.month() === anchor.value.month() && date.year() === anchor.value.year()
    })
    .sort()
    .map((key) => ({
      key,
      label: dayjs(key).format('M月D日 ddd'),
      isToday: key === todayKey,
      items: entriesByDate.value[key]
    })))

  load()

  return {
    loading, entries, anchor, monthLabel, todayKey,
    load, goPrevMonth, goNextMonth, goToday,
    entriesByDate, weeks, agenda
  }
}
