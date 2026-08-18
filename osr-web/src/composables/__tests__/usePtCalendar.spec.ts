import { describe, it, expect, vi, beforeEach } from 'vitest'
import dayjs from 'dayjs'

vi.mock('@/api/openlist/ptCalendar', () => ({ getPtCalendarApi: vi.fn() }))

import { usePtCalendar } from '../usePtCalendar'
import { getPtCalendarApi } from '@/api/openlist/ptCalendar'

/** load() 在 usePtCalendar() 里同步触发，等它跑完 */
const flush = () => new Promise((r) => setTimeout(r, 0))

const TODAY = dayjs().format('YYYY-MM-DD')
/** 本月里一个稳定存在的日子（1 号），用来构造肯定落在当前锚点月内的条目 */
const FIRST_OF_MONTH = dayjs().startOf('month').format('YYYY-MM-DD')

function entry(overrides: Record<string, any> = {}) {
  return {
    airDate: FIRST_OF_MONTH,
    subId: 1,
    tmdbId: '1',
    title: 'A 剧',
    posterPath: null,
    season: 1,
    episode: 1,
    state: 'MISSING',
    ...overrides
  }
}

beforeEach(() => {
  // 必须是 resetAllMocks 而不是 clearAllMocks：后者只清调用记录，
  // mockImplementationOnce 排的队会留到下一个用例里被当成它的第一次响应消费掉
  vi.resetAllMocks()
  ;(getPtCalendarApi as any).mockResolvedValue([entry()])
})

describe('usePtCalendar 翻月竞态', () => {
  /**
   * 翻月是「立刻改 anchor + 发一个请求」。连点几次就有多个请求在飞，
   * 而旧实现哪个后到就用哪个的数据——页面标题已经是 3 月、格子里铺的却是 2 月的排播，
   * 且没有任何异常迹象。
   */
  it('只采信最后一次请求的结果，先发后到的旧响应被丢弃', async () => {
    const first = entry({ title: '旧月份的剧' })
    const second = entry({ title: '新月份的剧' })
    let resolveFirst: (v: any) => void = () => {}
    let resolveSecond: (v: any) => void = () => {}

    ;(getPtCalendarApi as any)
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
      .mockImplementationOnce(() => new Promise((r) => { resolveSecond = r }))

    const c = usePtCalendar()          // 第 1 次请求（挂起）
    c.goNextMonth()                    // 第 2 次请求（挂起）

    // 后发的先回
    resolveSecond([second])
    await flush()
    expect(c.entries.value.map((e: any) => e.title)).toEqual(['新月份的剧'])

    // 先发的后回，必须被丢弃
    resolveFirst([first])
    await flush()
    expect(c.entries.value.map((e: any) => e.title)).toEqual(['新月份的剧'])
  })

  it('过期响应不会把 loading 提前收掉', async () => {
    let resolveFirst: (v: any) => void = () => {}
    ;(getPtCalendarApi as any)
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
      .mockImplementationOnce(() => new Promise(() => {}))

    const c = usePtCalendar()
    c.goNextMonth()

    resolveFirst([entry()])
    await flush()

    // 第二次请求还在飞，loading 应当仍为真
    expect(c.loading.value).toBe(true)
  })
})

describe('usePtCalendar 加载失败', () => {
  /** 失败时若只是清空结果，渲染出来就是「本月没有排播」，与真的没排播无法区分 */
  it('失败时 loadFailed 为真', async () => {
    (getPtCalendarApi as any).mockRejectedValue(new Error('boom'))
    const c = usePtCalendar()
    await flush()

    expect(c.loadFailed.value).toBe(true)
    expect(c.entries.value).toEqual([])
    expect(c.loading.value).toBe(false)
  })

  it('重试成功后失败态被清掉', async () => {
    (getPtCalendarApi as any).mockRejectedValueOnce(new Error('boom'))
    const c = usePtCalendar()
    await flush()
    expect(c.loadFailed.value).toBe(true)

    ;(getPtCalendarApi as any).mockResolvedValue([entry()])
    await c.load()
    expect(c.loadFailed.value).toBe(false)
    expect(c.entries.value).toHaveLength(1)
  })
})

describe('usePtCalendar 状态筛选', () => {
  it('按状态筛选，且计数恒按全量算', async () => {
    (getPtCalendarApi as any).mockResolvedValue([
      entry({ episode: 1, state: 'MISSING' }),
      entry({ episode: 2, state: 'IN_LIBRARY' }),
      entry({ episode: 3, state: 'IN_LIBRARY' })
    ])
    const c = usePtCalendar()
    await flush()

    expect(c.stateCounts.value).toEqual({ MISSING: 1, IN_LIBRARY: 2 })

    c.setState('MISSING')
    expect(c.visibleEntries.value.map((e: any) => e.episode)).toEqual([1])
    // 筛选后其余状态的计数不能变成 0，否则图例上的数字会随筛选跳动
    expect(c.stateCounts.value.IN_LIBRARY).toBe(2)
  })

  it('再点一次已选中的状态就取消筛选', async () => {
    const c = usePtCalendar()
    await flush()

    c.setState('MISSING')
    expect(c.activeState.value).toBe('MISSING')
    c.setState('MISSING')
    expect(c.activeState.value).toBe('')
  })

  it('筛选会同步影响按日期分组与移动端清单', async () => {
    (getPtCalendarApi as any).mockResolvedValue([
      entry({ episode: 1, state: 'MISSING' }),
      entry({ episode: 2, state: 'IN_LIBRARY' })
    ])
    const c = usePtCalendar()
    await flush()

    expect(c.entriesByDate.value[FIRST_OF_MONTH]).toHaveLength(2)
    c.setState('MISSING')
    expect(c.entriesByDate.value[FIRST_OF_MONTH]).toHaveLength(1)
    expect(c.agenda.value[0].items).toHaveLength(1)
  })
})

describe('usePtCalendar 本月判空', () => {
  /**
   * PC 端此前用 entries.length 判空，而它含着网格首尾溢出的上下月几天：
   * 「本月没有、上月末有」时 PC 不显示空态、本月格子却全空，移动端说「本月没有排播」——
   * 同一个月两端结论相反。
   */
  it('只有上下月溢出天有排播时，判定为本月无排播', async () => {
    const lastMonth = dayjs().startOf('month').subtract(1, 'day').format('YYYY-MM-DD')
    ;(getPtCalendarApi as any).mockResolvedValue([entry({ airDate: lastMonth })])
    const c = usePtCalendar()
    await flush()

    expect(c.entries.value).toHaveLength(1)
    expect(c.hasEntriesInMonth.value).toBe(false)
    // 移动端清单同样不含溢出天，两端口径一致
    expect(c.agenda.value).toHaveLength(0)
  })

  it('本月有排播时判定为有', async () => {
    const c = usePtCalendar()
    await flush()
    expect(c.hasEntriesInMonth.value).toBe(true)
  })
})

describe('usePtCalendar 今天与跳转', () => {
  it('today 初始为当天，网格里对应格子被标为今天', async () => {
    const c = usePtCalendar()
    await flush()

    expect(c.today.value).toBe(TODAY)
    const todayCell = c.weeks.value.flat().find((cell: any) => cell.key === TODAY)
    expect(todayCell?.isToday).toBe(true)
  })

  it('goMonth 跳到指定月份，非法值不改变锚点', async () => {
    const c = usePtCalendar()
    await flush()
    const before = c.monthLabel.value

    c.goMonth('2026-03')
    expect(c.monthLabel.value).toBe('2026 年 3 月')

    c.goMonth('不是月份')
    expect(c.monthLabel.value).toBe('2026 年 3 月')
    expect(before).not.toBe('')
  })

  it('goToday 把锚点拉回本月并返回今天的 key', async () => {
    const c = usePtCalendar()
    await flush()
    c.goMonth('2020-01')
    expect(c.monthLabel.value).toBe('2020 年 1 月')

    const key = c.goToday()
    expect(key).toBe(TODAY)
    expect(c.monthLabel.value).toBe(dayjs().format('YYYY 年 M 月'))
  })
})
