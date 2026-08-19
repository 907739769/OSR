// eslint-disable-next-line @typescript-eslint/ban-ts-comment -- 与同目录 design-system.spec.ts 同样的理由
// @ts-nocheck
import { describe, it, expect } from 'vitest'

/**
 * 模板里用到的自定义 class，必须在本页 `<style>` 或公共样式表里真的有定义。
 *
 * 起因是一次真实事故：批量改写页面的脚本用 LF 的模式串去替换 CRLF 文件，
 * `String.replace` 找不到就静默返回原串——于是**模板换掉了、样式一处都没落地**。
 * 页面上表现为图例变成浏览器默认按钮、日格还留着已经废弃的内嵌滚动、两个弹窗完全没样式，
 * 而 `vue-tsc`、eslint、既有单测全都是绿的：没有任何一环会去问「这个 class 有人定义吗」。
 *
 * 这条用例只回答那一个问题，因此刻意做得粗：只要能找到同名选择器就算通过，
 * 不校验属性是否合理。它挡的是「整块样式没了」，不是「样式写得对不对」。
 */

// 连页面目录下的子组件一起扫（如 ptSubscription/dialogs/*.vue）：模板搬进子组件、
// 样式落在页面 <style scoped> 里的话，样式对子组件根本不生效——而这正是本用例要挡的那类事故。
const pages = import.meta.glob('../../views/**/*.vue', {
  query: '?raw', import: 'default', eager: true
}) as Record<string, string>

const mobilePages = import.meta.glob('../../views-mobile/**/*.vue', {
  query: '?raw', import: 'default', eager: true
}) as Record<string, string>

const sharedStyles = import.meta.glob('../*.scss', {
  query: '?raw', import: 'default', eager: true
}) as Record<string, string>

const shared = Object.values(sharedStyles).join('\n')

/**
 * 只作测试选择器用的 class：它们靠 `wrapper.find('.x')` 定位，本身不需要样式。
 * 新增条目请确认它确实只用于测试——否则就是在给一个真的漏了样式的类开后门。
 */
const TEST_HOOK_ONLY = new Set([
  'batch-pause-btn', 'batch-resume-btn', 'batch-delete-btn',
  'batch-select-all-btn', 'batch-cancel-btn', 'batch-clear-btn',
  'batch-retry-btn', 'batch-blacklist-guid-btn', 'batch-blacklist-group-btn',
  'blacklist-guid-btn', 'blacklist-group-btn',
  'more-actions-trigger', 'sort-select'
])

/**
 * 这条用例上线时就已经存在的无主类名，按页面登记。
 * <p>
 * 它们是「样式删了、模板里的类名没跟着清」留下的（`renameDetail` 那批是并排对照块
 * 被删时留下的，见前端知识库里 `.rename-compare` 那条）。都只是死类名、不影响功能，
 * 但也确实没人给过样式——所以不假装它们没问题，登记出来等人顺手清掉。
 * </p>
 * <p>
 * <b>新增页面不要往这里加条目</b>：这份清单只为存量兜底，新写的模板应当当场补上样式。
 * </p>
 */
const KNOWN_GAPS: Record<string, string[]> = {
  'views/monitor/job': ['cron-desc'],
  // 日志弹窗从 job 页面整块搬进了子组件，那几个无主类名跟着一起搬
  'views/monitor/job/JobLogDialog': ['log-detail-dialog', 'log-table', 'mobile-card-title-row'],
  'views/openlist/ptDownloadRecord': ['selection-mode-btn'],
  'views-mobile/ptDownloadRecord': ['batch-toggle-btn'],
  'views-mobile/renameDetail': [
    'mobile-status-row', 'rename-filename-new', 'rename-filename-original',
    'rename-new-side', 'rename-original-side', 'rename-path-new', 'rename-path-original',
    'scrape-tag', 'status-tag'
  ]
}

/** Vuetify 自带类与工具类不归本页管 */
const isFrameworkClass = (name: string) =>
  /^(v-|mdi-|text-|bg-|ml-|mr-|mt-|mb-|my-|mx-|pa-|pl-|pr-|pt-|pb-|py-|px-|d-|justify-|align-|flex-|w-|h-|rounded|elevation-)/.test(name)

/** 一个合法的 class 名（排除模板表达式里被正则误捞的碎片） */
const isClassName = (name: string) => /^[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*$/.test(name)

function templateOf(src: string): string {
  const idx = src.indexOf('<style')
  return idx >= 0 ? src.slice(0, idx) : src
}

function styleOf(src: string): string {
  const idx = src.indexOf('<style')
  return idx >= 0 ? src.slice(idx) : ''
}

/** 收集模板里用到的 class：静态 class="a b"，以及 :class="{ 'a': x }" 的对象键 */
function usedClasses(template: string): Set<string> {
  const used = new Set<string>()
  for (const m of template.matchAll(/\bclass="([^"{}]+)"/g)) {
    m[1].split(/\s+/).forEach((c) => c && used.add(c))
  }
  for (const m of template.matchAll(/[{,]\s*'([^']+)'\s*:/g)) {
    used.add(m[1])
  }
  return used
}

/**
 * 判断某个 class 是否有定义。除了直接的 `.name`，还要认 SCSS 嵌套写法——
 * `.item-card-skeleton { &__poster { … } }` 定义的是 `item-card-skeleton__poster`。
 */
function isDefined(name: string, css: string): boolean {
  if (css.includes(`.${name}`)) return true
  const nested = name.match(/^(.*?)(__|--)(.+)$/)
  if (nested) {
    const [, parent, joiner, suffix] = nested
    return css.includes(`.${parent}`) && css.includes(`&${joiner}${suffix}`)
  }
  return false
}

const allPages = { ...pages, ...mobilePages }

describe('模板用到的 class 都有定义', () => {
  const cases = Object.entries(allPages).map(([path, src]) => {
    const name = path.replace(/^\.\.\/\.\.\//, '').replace(/\/index\.vue$/, '').replace(/\.vue$/, '')
    return [name, src] as const
  })

  it.each(cases)('%s', (name, src) => {
    const css = styleOf(src) + '\n' + shared
    const known = new Set(KNOWN_GAPS[name] || [])
    const missing = [...usedClasses(templateOf(src))]
      .filter(isClassName)
      .filter((c) => !isFrameworkClass(c))
      .filter((c) => !TEST_HOOK_ONLY.has(c))
      .filter((c) => !known.has(c))
      .filter((c) => !isDefined(c, css))
      .sort()

    expect(missing, `这些 class 在模板里用了，但本页 <style> 与公共样式表里都找不到定义`).toEqual([])
  })
})
