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

const styleSheets = import.meta.glob('../{list,mobile-list,index}.scss', {
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
    '.mobile-card', '.mobile-card-list'
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

  it('tokens.scss 不再有 Element Plus 的 light-1..9 阶梯命名', () => {
    expect(tokensCss).not.toMatch(/--osr-primary-light-\d/)
  })
})

describe('颜色走令牌', () => {
  // 允许写死色值的例外：刻意的装饰渐变、终端配色、ECharts 需要的具体色
  const ALLOW_LITERAL = [
    'views/openlist/ptSubscription/index.vue',
    'views-mobile/ptSubscription/index.vue',
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
