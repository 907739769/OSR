/**
 * 后端时间转相对时间（x 分钟前 / 小时前 / 天前），无效值原样返回。
 *
 * 这份实现原先是 `views/dashboard/RecentFailuresCard.vue` 里的私有函数，媒体服务器配置页
 * 要用同一套说法时提了出来。收口的理由与项目里其它几处相同：复制出来的两份迟早漂移，
 * 而漂移的表现是「同一个时间在首页显示 3 分钟前、在配置页显示 4 分钟前」这类没人会去报的小错。
 *
 * @param time 后端返回的时间串（`yyyy-MM-dd HH:mm:ss`）
 * @param now  当前时刻，默认取 `Date.now()`；做成参数是为了让测试能钉住这里的算术
 */
export function formatRelativeTime(time: string | null | undefined, now: number = Date.now()): string {
  if (!time) return ''
  // Safari 与部分 iOS 版本不认 `yyyy-MM-dd HH:mm:ss` 里的空格，换成 T 才解析得出来；
  // 不换的话这里会返回 NaN，页面上显示的是原始时间串——不报错，只是相对时间静默失效
  const t = new Date(time.replace(' ', 'T')).getTime()
  if (Number.isNaN(t)) return time
  const min = Math.floor((now - t) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  return new Date(t).toLocaleDateString('zh-CN')
}
