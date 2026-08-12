import request from '@/api/request'

/** 追剧日历里的一集，字段与后端 CalendarEntry 一一对应 */
export interface CalendarEntry {
  airDate: string
  subId: number
  tmdbId: string
  title: string
  posterPath: string | null
  season: number
  episode: number
  state: string
}

/** 查询日期区间内的排播，start/end 均为 yyyy-MM-dd 且含首尾两天 */
export function getPtCalendarApi(start: string, end: string) {
  return request.get<any, CalendarEntry[]>('/openliststrm/pt-calendar', { params: { start, end } })
}
