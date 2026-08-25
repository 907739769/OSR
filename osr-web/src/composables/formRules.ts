/**
 * 把 composable 里声明的规则对象转换成 Vuetify `:rules` 要的函数数组。
 *
 * 业务 composable（`useTaskList` 的 `rules` 配置）用的是 RuoYi 遗留的对象格式
 * `{ required, pattern, type, min, max, message, trigger }`，而 Vuetify 的
 * `v-text-field :rules` 收的是 `(value) => true | string`。转换这件事本身没有
 * 任何页面级判断，**因此只能有一份**。
 *
 * 收口前它在 14 个页面里各写一份，而且分化成了 4 种实现：
 *   - 只判 required（ptAutoAddRule / ptTorrentBlacklist / wecomUser 六份）
 *   - required + pattern（ptMediaServer / ptTransferRule 四份）
 *   - required + pattern + 数字下限（ptIndexer 两份）
 *   - required + 数字上下限，**不判 pattern**（ptDownloader 两份）
 * 每一份恰好只覆盖「自己那个 composable 当前用到的规则种类」。于是往
 * `usePtMediaServer` 的规则里加一条 `min`、或往 `usePtDownloader` 里加一条
 * `pattern`，那条规则会**静默失效**：不报错、不告警，表单照常提交，只是校验没了。
 */

export interface FieldRule {
  required?: boolean
  pattern?: RegExp
  type?: 'number'
  min?: number
  max?: number
  message?: string
  /** RuoYi 遗留字段，Vuetify 不需要，保留只为兼容既有规则声明 */
  trigger?: string
}

export type VuetifyRule = (value: any) => true | string

export function toRuleFns(ruleList?: FieldRule[]): VuetifyRule[] {
  return (ruleList || []).map((rule) => (value: any) => {
    const empty = value === null || value === undefined || value === ''
    if (rule.required && empty) return rule.message || '不能为空'
    // 非必填字段留空一律放行，且必须早于下面的数字判定：Number('') === 0，
    // 不挡的话一个 min: 1 的选填字段会在留空时报「不得小于 1」，用户没法把它清空
    if (empty) return true

    if (rule.pattern && !rule.pattern.test(String(value))) {
      return rule.message || '格式不正确'
    }
    if (rule.type === 'number') {
      const num = Number(value)
      if (Number.isNaN(num)) return rule.message || '请输入数字'
      if (rule.min !== undefined && num < rule.min) return rule.message || `不得小于 ${rule.min}`
      if (rule.max !== undefined && num > rule.max) return rule.message || `不得大于 ${rule.max}`
    }
    return true
  })
}
