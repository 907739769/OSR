import { describe, it, expect } from 'vitest'
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
} from '../realtimeLog'

const line = (p: Partial<LogLine>): LogLine => ({ id: 1, level: 'INFO', msg: '', ...p })

describe('realtimeLog', () => {
  describe('ResumeFilter（重连去重）', () => {
    it('不晚于断线前最后一条的历史行丢掉，续行跟随首行', () => {
      const r = new ResumeFilter('2026-09-22 10:00:01.000')
      const history = [
        line({ ts: '2026-09-22 10:00:00.000', msg: 'old' }),
        line({ cont: true, msg: '\tat old.Stack' }),
        line({ ts: '2026-09-22 10:00:01.000', msg: 'last-seen' }),
        line({ ts: '2026-09-22 10:00:02.000', msg: 'new' }),
        line({ cont: true, msg: '\tat new.Stack' }),
      ]
      expect(history.filter(l => r.accept(l)).map(l => l.msg)).toEqual(['new', '\tat new.Stack'])
      expect(r.sawOverlap).toBe(true)
    })

    it('历史全部晚于断线点时 sawOverlap 为 false：断线期间日志超过回推行数，中间有缺口', () => {
      const r = new ResumeFilter('2026-09-22 10:00:00.000')
      expect(r.accept(line({ ts: '2026-09-22 10:05:00.000' }))).toBe(true)
      expect(r.sawOverlap).toBe(false)
    })
  })

  describe('buildMatcher', () => {
    it('空关键字不过滤，非法正则返回 undefined（不清屏）', () => {
      expect(buildMatcher('  ', false)).toBeNull()
      expect(buildMatcher('(', true)).toBeUndefined()
    })

    it('普通模式按字面匹配（不把 . 当通配），且匹配 traceId', () => {
      const m = buildMatcher('a.b', false)!
      expect(m.test(line({ msg: 'axb' }))).toBe(false)
      expect(m.test(line({ msg: 'x a.b y' }))).toBe(true)
      const t = buildMatcher('7f3a', false)!
      expect(t.test(line({ trace: '7f3a', msg: 'nothing' }))).toBe(true)
    })

    it('高亮正则可反复使用（lastIndex 由 splitParts 重置）', () => {
      const m = buildMatcher('ab', false)!
      expect(splitParts('xabx', m.highlight)).toEqual([
        { text: 'x', hit: false }, { text: 'ab', hit: true }, { text: 'x', hit: false },
      ])
      expect(splitParts('abab', m.highlight).filter(p => p.hit)).toHaveLength(2)
    })
  })

  describe('splitParts', () => {
    it('能匹配空串的正则不死循环', () => {
      expect(splitParts('abc', /x*/gi)).toEqual([{ text: 'abc', hit: false }])
    })

    it('无正则时整段返回', () => {
      expect(splitParts('abc', null)).toEqual([{ text: 'abc', hit: false }])
    })
  })

  describe('级别', () => {
    const all = { DEBUG: true, INFO: true, WARN: true, ERROR: true }

    it('TRACE 跟随 Info', () => {
      expect(levelEnabled('TRACE', { ...all, INFO: false })).toBe(false)
      expect(levelEnabled('TRACE', all)).toBe(true)
    })

    it('levels 参数：全选不传、全不选传 -、否则逗号列表', () => {
      expect(levelsParam(all)).toBeNull()
      expect(levelsParam({ DEBUG: false, INFO: false, WARN: false, ERROR: false })).toBe('-')
      expect(levelsParam({ ...all, DEBUG: false })).toBe('INFO,WARN,ERROR')
    })
  })

  it('keepLast 不超限时返回原数组', () => {
    const a = [1, 2, 3]
    expect(keepLast(a, 5)).toBe(a)
    expect(keepLast(a, 2)).toEqual([2, 3])
  })

  it('formatLine 还原成日志文件里的格式', () => {
    expect(formatLine(line({ ts: '2026-09-22 10:00:00.123', trace: 't1', level: 'INFO', logger: 'Foo', msg: 'bar' })))
      .toBe('[2026-09-22 10:00:00.123][t1][INFO ][Foo] bar')
    expect(formatLine(line({ cont: true, msg: '\tat x' }))).toBe('\tat x')
  })

  it('toLogLine 兜底缺省字段', () => {
    expect(toLogLine({ msg: 'x' }, 7)).toEqual({ id: 7, ts: undefined, trace: '', level: 'INFO', logger: '', msg: 'x', cont: false })
  })
})
