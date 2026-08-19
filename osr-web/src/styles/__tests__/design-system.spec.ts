import { describe, it, expect } from 'vitest'

/**
 * 设计系统结构一致性测试。
 *
 * 这些断言挡住的是「改造完又慢慢散掉」的那类回归：共享类被页面重新定义、
 * 颜色写死绕过令牌、Element Plus 时代的类名/命名死灰复燃。
 * 每条规则都对应审计里实际发现过的问题，不是凭空立规矩。
 *
 * 用 import.meta.glob 读源码而不是 node:fs —— 项目没装 @types/node，
 * 走 Vite 的能力就不用为一个测试引依赖。
 */

const pages = import.meta.glob('../../{views,views-mobile}/**/*.vue', {
  query: '?raw',
  import: 'default',
  eager: true
}) as Record<string, string>

const shared = import.meta.glob('../../{components,layouts}/**/*.vue', {
  query: '?raw',
  import: 'default',
  eager: true
}) as Record<string, string>

const tokensCss = Object.values(
  import.meta.glob('../tokens.scss', { query: '?raw', import: 'default', eager: true })
)[0] as string

const styleSheets = import.meta.glob('../{list,mobile-list,menu,index}.scss', {
  query: '?raw',
  import: 'default',
  eager: true
}) as Record<string, string>

/** ../../views/openlist/x/index.vue -> views/openlist/x/index.vue */
const rel = (p: string) => p.replace(/^\.\.\/\.\.\//, '')

const pageEntries = Object.entries(pages).map(([p, src]) => [rel(p), src] as const)
const allVue = [...Object.entries(pages), ...Object.entries(shared)].map(
  ([p, src]) => [rel(p), src] as const
)

/** 取出 <style scoped> 里的内容 */
function scopedStyle(src: string): string {
  const m = src.match(/<style scoped[^>]*>([\s\S]*?)<\/style>/)
  return m ? m[1] : ''
}

/**
 * 顶层选择器中「自己带了属性声明」的那些。
 *
 * 只挂嵌套子规则（例如 `.task-card { .card-sub { … } }`）属于页面在共享卡片上
 * 追加自己的子元素样式，是允许的；真正要挡的是把共享类的 background / padding /
 * border-radius 这些又抄一遍。
 */
function redefinedTopLevelSelectors(css: string): string[] {
  const sels: string[] = []
  let i = 0
  while (i < css.length) {
    if (css.startsWith('/*', i)) {
      const j = css.indexOf('*/', i)
      i = j === -1 ? css.length : j + 2
      continue
    }
    const open = css.indexOf('{', i)
    if (open === -1) break
    const sel = css.slice(i, open).trim().split('\n').pop()!.trim()
    let depth = 1
    let k = open + 1
    while (k < css.length && depth) {
      if (css[k] === '{') depth++
      else if (css[k] === '}') depth--
      k++
    }
    const body = css.slice(open + 1, k - 1)
    const ownDecls = body.replace(/[^{}]*\{(?:[^{}]|\{[^{}]*\})*\}/g, '')
    if (/[\w-]+\s*:/.test(ownDecls)) sels.push(sel)
    i = k
  }
  return sels
}

describe('共享样式单源', () => {
  // styles/list.scss 与 styles/mobile-list.scss 提供的类，页面里不允许再整块定义一遍
  const SHARED = [
    '.page-container', '.search-card', '.table-card', '.action-bar', '.batch-toolbar',
    '.pagination-wrapper', '.search-fields', '.inline-fields',
    '.path-box', '.path-row', '.path-label', '.path-text', '.path-name',
    '.card-grid', '.item-card',
    '.mobile-page', '.task-list', '.task-card', '.fab-add', '.batch-bar',
    '.card-actions', '.drawer-actions', '.date-range-fields',
    '.mobile-card', '.mobile-card-list',
    '.menu-item', '.menu-group-label'
  ]

  it('页面不重新定义共享类', () => {
    const offenders = pageEntries
      .map(([name, src]) => {
        const dup = redefinedTopLevelSelectors(scopedStyle(src)).filter((s) => SHARED.includes(s))
        return dup.length ? `${name}: ${dup.join(', ')}` : null
      })
      .filter(Boolean)
    expect(offenders).toEqual([])
  })
})

describe('Element Plus 残留', () => {
  it('没有 el-* 组件与 --el-* 变量', () => {
    expect(allVue.filter(([, s]) => /<el-|--el-[\w-]+/.test(s)).map(([n]) => n)).toEqual([])
  })

  it('没有 .modern-dialog —— 它是覆盖 el-dialog 时代的死类，全局无定义', () => {
    expect(allVue.filter(([, s]) => s.includes('modern-dialog')).map(([n]) => n)).toEqual([])
  })

  it('没有手写的 el-form-item 复刻（.form-item / .form-label / .field-label），统一用 FormField', () => {
    const bad = allVue
      .filter(([, s]) => /class="(form-item|form-label|field-label)[ "]/.test(s))
      .map(([n]) => n)
    expect(bad).toEqual([])
  })

  it('没有自造的搜索区/表单项平行类（.search-form-row / .rule-field-label），分别用 .search-fields 与 FormField', () => {
    const bad = allVue
      .filter(([, s]) => /(class="(search-form-row|rule-field-label)[ "]|\.(search-form-row|rule-field-label)\s*\{)/.test(s))
      .map(([n]) => n)
    expect(bad).toEqual([])
  })

  it('tokens.scss 不再有 Element Plus 的 light-1..9 阶梯命名', () => {
    expect(tokensCss).not.toMatch(/--osr-primary-light-\d/)
  })
})

describe('颜色走令牌', () => {
  // 允许写死色值的例外：刻意的装饰渐变、终端配色、ECharts 需要的具体色
  const ALLOW_LITERAL = [
    'views/openlist/ptSubscription/index.vue',
    'views-mobile/ptSubscription/index.vue',
    // 海报占位的装饰渐变，随订阅卡一起拆进了子组件
    'views/openlist/ptSubscription/SubscriptionCard.vue',
    'views-mobile/ptSubscription/SubscriptionCard.vue',
    'views/monitor/log/realtime.vue',
    'views/auth/Login.vue',
    'views/dashboard/desktop.vue',
    'views/openlist/ptStatsDashboard/index.vue',
    'views-mobile/ptStatsDashboard/index.vue',
    'views-mobile/dashboard/index.vue'
  ]

  it('页面样式里不写死十六进制颜色', () => {
    const offenders = pageEntries
      .filter(([name]) => !ALLOW_LITERAL.includes(name))
      .map(([name, src]) => {
        const hex = scopedStyle(src).match(/#[0-9a-fA-F]{3,8}\b/g) ?? []
        return hex.length ? `${name}: ${hex.join(', ')}` : null
      })
      .filter(Boolean)
    expect(offenders).toEqual([])
  })

  it('所有 var(--osr-*) 都在 tokens.scss 里有定义', () => {
    const defined = new Set([...tokensCss.matchAll(/^\s*(--osr-[\w-]+)\s*:/gm)].map((m) => m[1]))
    const missing = new Set<string>()
    for (const [name, src] of [...allVue, ...Object.entries(styleSheets).map(([p, s]) => [rel(p), s] as const)]) {
      for (const m of src.matchAll(/var\((--osr-[\w-]+)/g)) {
        if (!defined.has(m[1])) missing.add(`${m[1]} @ ${name}`)
      }
    }
    expect([...missing]).toEqual([])
  })
})

describe('搜索区紧凑度', () => {
  /**
   * 搜索区的输入框必须写 hide-details。
   *
   * 不写的话 Vuetify 会给每个输入框在下方预留 details/hint 行（约 22px），
   * 移动端搜索面板再叠上 .search-panel-body 的 margin-bottom: 12px，
   * 四个字段就多出近百像素空白，观感上「过于松散」。
   * 审计时移动端 10 个页面共 32 处漏写，PC 端一处不漏——差别只在有没有人照着
   * 已有页面抄，所以这条得由测试守着而不是靠自觉。
   *
   * 搜索区的字段都没有校验规则（有 rules 的是弹窗表单，不在这两个片段里），
   * 因此 hide-details 不会吞掉任何错误提示。
   */
  const FIELD = /<(v-text-field|v-select|v-autocomplete|v-combobox)\b/g

  /** 取出 open 与 close 之间的模板片段 */
  const section = (src: string, open: string, close: string): string => {
    const a = src.indexOf(open)
    if (a < 0) return ''
    const b = src.indexOf(close, a)
    return b < 0 ? '' : src.slice(a, b)
  }

  /** 片段里缺 hide-details 的字段（按字段起始位置切块，逐块查属性） */
  const missingHideDetails = (block: string): string[] => {
    const starts = [...block.matchAll(FIELD)].map((m) => m.index as number)
    return starts
      .map((s, i) => block.slice(s, starts[i + 1] ?? block.length))
      .filter((chunk) => !chunk.includes('hide-details'))
      .map((chunk) => chunk.match(/label="([^"]*)"/)?.[1] ?? '(无 label)')
  }

  const offendersIn = (open: string, close: string) =>
    pageEntries
      .map(([name, src]) => {
        const bad = missingHideDetails(section(src, open, close))
        return bad.length ? `${name}: ${bad.join(', ')}` : null
      })
      .filter(Boolean)

  it('移动端 MobileSearchPanel 里的字段都写了 hide-details', () => {
    expect(offendersIn('<MobileSearchPanel', '</MobileSearchPanel>')).toEqual([])
  })

  // PC 的搜索区已收进 components/SearchPanel.vue，字段作为默认插槽传进去；
  // 这里的起止标记必须跟着改，否则 section() 找不到片段、这条用例会变成永远通过的空检查
  it('PC 端 SearchPanel 里的字段都写了 hide-details', () => {
    expect(offendersIn('<SearchPanel', '</SearchPanel>')).toEqual([])
  })
})
