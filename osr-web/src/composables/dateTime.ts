/**
 * 后端时间串（`yyyy-MM-dd HH:mm:ss`，Jackson 按 GMT+8 序列化）的绝对时间格式化与耗时计算。
 *
 * 与 `relativeTime.ts` 分工：那份说「多久以前」，这份说「几点几分」「跑了多久」。
 * 提成公共函数的直接原因是定时任务页与它的执行记录弹窗各需要一份——而两份里
 * **只有一份记得处理 Safari**：`new Date('2026-09-20 03:00:00')` 在 iOS Safari 上是
 * Invalid Date，必须把中间那个空格换成 T。漏掉这条不会报错，只是时间在手机上原样
 * 显示成后端那串，耗时变成 '-'，而本项目是装到手机上用的 PWA。
 */

/** 解析后端时间串；解析不出来返回 null（调用方各自决定怎么兜底） */
function parse(time: string | null | undefined): Date | null {
  if (!time) return null
  const date = new Date(String(time).replace(' ', 'T'))
  return Number.isNaN(date.getTime()) ? null : date
}

const pad = (n: number) => n.toString().padStart(2, '0')

/**
 * 绝对时间。
 *
 * @param time     后端时间串
 * @param fallback 解析不出来时的占位，默认 '-'
 * @param withYear 是否带年份。列表里的「下次执行」省掉年份更好扫读，详情里要完整时间
 */
export function formatDateTime(
  time: string | null | undefined,
  fallback = '-',
  withYear = true
): string {
  const date = parse(time)
  if (!date) return time ? String(time) : fallback
  const md = `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  return withYear ? `${date.getFullYear()}-${md}` : md
}

/** 两个时间串之间的耗时，任一侧缺失返回 '-' */
export function formatDuration(start: string | null | undefined, end: string | null | undefined): string {
  const s = parse(start)
  const e = parse(end)
  if (!s || !e) return '-'
  const diff = Math.abs(e.getTime() - s.getTime())
  const sec = Math.floor(diff / 1000) % 60
  const min = Math.floor(diff / 60000)
  if (min > 0) return `${min}分${sec}秒`
  if (sec > 0) return `${sec}.${(diff % 1000).toString().padStart(3, '0').slice(0, 1)}秒`
  return `${diff}毫秒`
}
