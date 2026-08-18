/**
 * 体积单位换算：后端一律存字节（Long），前端表单一律以 GB 展示与输入。
 *
 * 全站只此一份，PT 全局过滤规则（usePtFilterConfig）与订阅级过滤覆盖（usePtSubscription）
 * 共用；两边各写一份会漂移，而漂移的表现是「同一个阈值在全局页和订阅页显示得不一样」。
 */

/** 1 GB = 1073741824 字节 */
export const GB = 1073741824

/**
 * 展示精度：两位小数 ≈ 10 MB 粒度，与体积输入框的 `step="0.01"` 对齐。
 * 两者必须一致——回填的值不满足 step 约束时，浏览器会把一个刚刚存进去的合法值
 * 标成「请输入有效值」，改精度时输入框那侧要一起改。
 */
const GB_SCALE = 100

/**
 * 字节 → GB（回填表单用）。
 *
 * 保留两位小数而不是取整：取整会把 500MB 这类小于 1GB 的阈值抹成 0，
 * 而 0 在过滤规则里的语义是「不限」——阈值不是变粗了，是静默失效了。
 */
export const bytesToGb = (bytes?: number | null): number => {
  const value = Number(bytes)
  if (!Number.isFinite(value) || value <= 0) return 0
  return Math.round((value / GB) * GB_SCALE) / GB_SCALE
}

/**
 * GB → 字节（提交用）。
 *
 * 必须取整：后端字段是 Long，0.01 GB 折算出来是 10737418.24，带小数的 JSON 数字
 * 反序列化到 Long 会直接失败。
 */
export const gbToBytes = (gb?: number | null): number => {
  const value = Number(gb)
  if (!Number.isFinite(value) || value <= 0) return 0
  return Math.round(value * GB)
}
