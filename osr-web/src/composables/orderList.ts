/**
 * 「有序列表」这类配置的纯函数：逗号分隔串 ↔ 数组、上移下移、按全集补齐。
 *
 * PT 过滤规则（排序维度、三个优先级列表）与洗版规则（比较维度）都是「一串有先后的值、
 * 存成逗号分隔串」。这几段逻辑原先在两个 composable 里各写一份。
 */

/** 逗号分隔串 → 去空白、去空项的数组 */
export function splitCsv(csv?: string | null): string[] {
  if (!csv) return []
  return csv
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

export function joinCsv(list: string[]): string {
  return list.join(',')
}

/** 返回把 index 处的项上移（delta=-1）或下移（delta=1）一位后的新数组，越界时原样返回副本 */
export function moveItem<T>(list: T[], index: number, delta: -1 | 1): T[] {
  const next = [...list]
  const target = index + delta
  if (index < 0 || index >= next.length || target < 0 || target >= next.length) return next
  ;[next[index], next[target]] = [next[target], next[index]]
  return next
}

/**
 * 已配置的顺序在前（只保留全集里认识的），全集里没出现在配置中的补到末尾——
 * 后端新增维度后，存量配置里没有它，不补的话它在页面上直接消失。
 */
export function mergeOrder(csv: string | undefined | null, all: string[]): string[] {
  const configured = splitCsv(csv).filter((s) => all.includes(s))
  const unique = [...new Set(configured)]
  return [...unique, ...all.filter((d) => !unique.includes(d))]
}
