import { describe, it, expect } from 'vitest'

/**
 * PC / 移动端功能对齐测试。
 *
 * 审计里最常见的一类问题是「新功能只做了一端」：PC 有批量删除移动端没有、
 * PC 能拉黑移动端不能……这类差异靠人工比对很难长期维持。
 *
 * 这里的做法是比对两端从同一个 composable 里解构出来的 handler 集合：
 * 一端用了、另一端没用的，要么补上，要么在 ALLOWED_GAPS 里显式登记原因。
 * 登记本身就是一次 review —— 挡住的是「悄悄漏掉」，不是「有意不做」。
 */

// 用 import.meta.glob 读源码而不是 node:fs —— 项目没装 @types/node
// PC 页分散在 views/openlist（业务）与 views/system（系统管理）两处，两边都要扫——
// 只扫 openlist 的话，system 下的成对页面（wecomUser / notifyRoute）会悄悄漏出检查范围
// 连页面目录下的子组件一起读（如 ptSubscription/dialogs/*.vue）：页面被拆成组件之后，
// 动作是在子组件里解构的，只读 index.vue 会把「拆分」误报成「这一端少了 9 个功能」。
const pcPages = {
  ...import.meta.glob('../../views/openlist/*/**/*.vue', {
    query: '?raw', import: 'default', eager: true
  }),
  ...import.meta.glob('../../views/system/*/**/*.vue', {
    query: '?raw', import: 'default', eager: true
  })
} as Record<string, string>

const mobilePages = import.meta.glob('../../views-mobile/*/**/*.vue', {
  query: '?raw', import: 'default', eager: true
}) as Record<string, string>

/** 有 PC / 移动端两套实现的页面（与 router/index.ts 里 createDeviceView 的清单一致） */
const PAIRS = [
  'strmTask', 'strmRecord', 'copyTask', 'copyRecord',
  'renameTask', 'renameDetail', 'renameOrphan', 'renameConfig',
  'ptIndexer', 'ptDownloader', 'ptMediaServer', 'ptSubscription',
  'ptDownloadRecord', 'ptStatsDashboard', 'ptTorrentBlacklist', 'ptAutoAddRule', 'ptCalendar', 'ptHealth',
  'ptTransferRule', 'notifyRoute', 'wecomUser'
]

/**
 * 「选择 / 分页」这层交互外壳按设备实现本来就不同，不算功能差异：
 * PC 用 v-data-table 的表头全选，移动端是卡片逐个勾选 + MobilePager。
 */
const SHELL_ONLY = new Set([
  'handleSelectionChange', // PC 表格选中回调
  'toggleSelect', 'handleCardClick', 'clearSelection', // 移动端卡片选中
  // 全选本页：PC 走 v-data-table 表头自带的全选框，不经过 composable；只有卡片网格型
  // 页面（ptIndexer/ptDownloader/ptMediaServer/ptSubscription/ptDownloadRecord）两端都用它
  'toggleSelectAllPage',
  'handleSizeChange', // 移动端 MobilePager 换页大小（PC 由表格内置分页处理）
  // 移动端卡片「更多」底部面板（useActionSheet）。PC 是操作列里的「更多 ▾」下拉菜单，
  // 装的是同一批动作 —— 差的只是这层容器怎么弹出来，不是功能。
  'openSheet',
  // 搜索区展开状态（PC 走 useSearchPanel，移动端是 MobileSearchPanel 自己的 collapsed）。
  // 两端都有这个功能，只是 PC 侧把它做成了 composable 才被这条规则扫到。
  'showSearch'
])

/**
 * 同一个功能在两端叫了不同名字。左边 PC、右边移动端。
 * 出现在这张表里 = 功能是齐的，只是 composable 暴露的入口不同名。
 */
const EQUIVALENT: Array<[string, string]> = [
  ['handleExecute', 'handleBatchExecute'], // 批量执行
  ['handleDelete', 'handleBatchDelete'] // 批量删除（PC 传 undefined 走选中项）
]

/**
 * 真正只有一端有、且经过评审确认不需要补的。key 是 `页面:标识`，value 是原因。
 * 新增条目请写清为什么这一端不需要 —— 这份清单就是差异的评审记录。
 */
const ALLOWED_GAPS: Record<string, string> = {
  'ptSubscription:handleMoreCommand': 'PC 用「更多」下拉分发，移动端是底部操作抽屉直接调用各动作，功能等价',
  'notifyRoute:toggleChannel': 'PC 是「类型×渠道」矩阵，每列一个渠道，故有整列开关；'
    + '移动端横向放不下矩阵，改成一个通知类型一张卡、卡内逐渠道列出，没有「列」这个概念。'
    + '按类型整行开关(toggleType)两端都有'
}

function readPage(kind: 'views' | 'views-mobile', name: string): string | null {
  const map = kind === 'views' ? pcPages : mobilePages
  const keys = Object.keys(map).filter((k) => k.includes(`/${name}/`))
  if (!keys.length) return null
  // 连页面 import 进来的本地组件一起读（见下方 sourcesWithImports 的注释）：
  // 表单弹窗抽成 components/dialogs/ 下的共用件之后，submitForm / handleTest 这些动作
  // 就不在页面自己的解构里了，只读页面目录会让这条用例的覆盖面悄悄缩水且没有任何报错。
  // 共用件被两端同时读到，它贡献的动作在两侧互相抵消，因此不会造成误报。
  const own = keys.map((k) => map[k]).join('\n')
  const prefixes = kind === 'views'
    ? [`views/openlist/${name}/`, `views/system/${name}/`]
    : [`views-mobile/${name}/`]
  return `${own}\n${sourcesWithImports(prefixes)}`
}

/**
 * 取出 `const { a, b, c } = useXxx(...)` 里解构出来的标识符。
 * 右括号不参与匹配：composable 允许带参数（如 PC 端 `usePtDownloadRecord({ autoLoad: false })`），
 * 只认空括号会让那一端整个解构块被漏读，测试报成「这一端什么动作都没有」。
 */
function destructuredFromComposable(src: string): Set<string> {
  const out = new Set<string>()
  for (const m of src.matchAll(/const\s*\{([\s\S]*?)\}\s*=\s*use[A-Z]\w*\(/g)) {
    for (const raw of m[1].split(',')) {
      const id = raw.split(':')[0].replace(/\/\/.*$/gm, '').trim()
      if (/^[a-zA-Z_$][\w$]*$/.test(id)) out.add(id)
    }
  }
  return out
}

/** 只关心「动作」——handleXxx / openXxx / doXxx / toggleXxx / showXxx / pushXxx */
const isAction = (id: string) => /^(handle|open|do|toggle|show|push|confirm|save|reset|clear)[A-Z]/.test(id)

describe('PC / 移动端功能对齐', () => {
  it.each(PAIRS)('%s：两端使用的业务动作一致（差异需在 ALLOWED_GAPS 登记）', (name) => {
    const pc = readPage('views', name)
    const mb = readPage('views-mobile', name)
    expect(pc, `缺少 PC 实现：views/openlist/${name}/index.vue`).toBeTruthy()
    expect(mb, `缺少移动端实现：views-mobile/${name}/index.vue`).toBeTruthy()

    const pcIds = destructuredFromComposable(pc!)
    const mbIds = destructuredFromComposable(mb!)

    // 把「换了名字的同一个功能」折算成对端也有
    const covers = (mine: Set<string>, theirs: Set<string>, id: string) =>
      theirs.has(id) ||
      EQUIVALENT.some(([a, b]) =>
        (id === a && (theirs.has(b) || mine.has(b))) || (id === b && (theirs.has(a) || mine.has(a)))
      )

    const keep = (id: string) => isAction(id) && !SHELL_ONLY.has(id) && !ALLOWED_GAPS[`${name}:${id}`]
    const onlyPc = [...pcIds].filter((id) => keep(id) && !covers(pcIds, mbIds, id))
    const onlyMb = [...mbIds].filter((id) => keep(id) && !covers(mbIds, pcIds, id))

    expect(
      { 仅PC有: onlyPc, 仅移动端有: onlyMb },
      `${name} 两端动作不一致；补齐缺失的一端，或在 ALLOWED_GAPS 里写明为什么不需要`
    ).toEqual({ 仅PC有: [], 仅移动端有: [] })
  })
})

/**
 * ── 表单字段对齐 ──────────────────────────────────────────────────────
 * 上面那条只比对从 composable 解构出的**动作**，模板里的表单字段完全在它视野之外。
 * 实测漏掉过一处：`views-mobile/ptIndexer` 的弹窗没有 hrEnabled / hrSeedHours / hrRatio
 * 三个字段，移动端**新建**索引器时配不了 H&R。这类缺口没有任何错误现象——编辑已有记录时
 * 值靠 `form = { ...task }` 整体回填，连数据都不会被抹掉，接口与日志一切正常，
 * 只有把两端模板并排摆着逐字段数才发现得了。
 *
 * 比对的是模板里绑到表单对象上的字段（`v-model="form.x"`，含 `.number`/`.trim` 修饰符
 * 与 `:model-value` 写法）。**表单对象名一并参与比较**：同一个页面常有 form / retryForm /
 * batchForm 几个并存，只比字段名会让「PC 的 batchForm.a」与「移动端的 form.a」互相抵消。
 */

// 跟随 import 展开：弹窗抽成共用组件（`components/dialogs/*.vue`）之后，字段就不在
// views/ 与 views-mobile/ 的 glob 范围内了。不跟随的话两端各自都读不到那些字段，
// 而两个空集合恰好"相等"——这条用例会静默退化成永远通过的空检查，
// 且正好退化在它最该起作用的时候（共用组件里一改，两端一起变）。
const allVueSources: Record<string, string> = {}
for (const [key, src] of Object.entries(
  import.meta.glob('../../**/*.vue', { query: '?raw', import: 'default', eager: true })
) as [string, string][]) {
  allVueSources[key.replace(/^(\.\.\/)+/, '')] = src
}

/** `@/a/b.vue` 或 `./XxxDialog.vue` → 相对 src/ 的路径；第三方包与非 .vue 返回 null */
function resolveVuePath(fromPath: string, spec: string): string | null {
  if (!spec.endsWith('.vue')) return null
  if (spec.startsWith('@/')) return spec.slice(2)
  if (!spec.startsWith('.')) return null
  const segments = fromPath.split('/').slice(0, -1)
  for (const part of spec.split('/')) {
    if (part === '.') continue
    if (part === '..') segments.pop()
    else segments.push(part)
  }
  return segments.join('/')
}

/** 页面目录下的全部 .vue + 它们（递归）import 进来的本地 .vue 组件源码 */
function sourcesWithImports(entryPrefixes: string[]): string {
  const queue = Object.keys(allVueSources).filter((p) => entryPrefixes.some((e) => p.startsWith(e)))
  const seen = new Set(queue)
  const out: string[] = []
  while (queue.length) {
    const path = queue.shift()!
    const src = allVueSources[path]
    if (!src) continue
    out.push(src)
    for (const m of src.matchAll(/from\s*['"]([^'"]+\.vue)['"]/g)) {
      const next = resolveVuePath(path, m[1])
      if (next && allVueSources[next] && !seen.has(next)) {
        seen.add(next)
        queue.push(next)
      }
    }
  }
  return out.join('\n')
}

/** 模板里绑到表单对象上的字段，形如 `form.name` / `retryForm.count` */
function formFields(src: string): Set<string> {
  const out = new Set<string>()
  const binding = /(?::model-value|v-model(?::[a-zA-Z-]+)?(?:\.[a-z]+)*)="([A-Za-z]*[Ff]orm)\.([A-Za-z0-9_]+)"/g
  for (const m of src.matchAll(binding)) out.add(`${m[1]}.${m[2]}`)
  return out
}

/**
 * 真正只有一端有、且经过评审确认不需要补的表单字段。key 是 `页面:表单对象.字段`。
 * 与 ALLOWED_GAPS 一样，登记本身就是一次评审——请写清为什么这一端不需要它。
 */
const ALLOWED_FIELD_GAPS: Record<string, string> = {}

describe('PC / 移动端表单字段对齐', () => {
  it.each(PAIRS)('%s：两端可填写的表单字段一致（差异需在 ALLOWED_FIELD_GAPS 登记）', (name) => {
    const pcSrc = sourcesWithImports([`views/openlist/${name}/`, `views/system/${name}/`])
    const mbSrc = sourcesWithImports([`views-mobile/${name}/`])
    expect(pcSrc, `缺少 PC 实现：views/*/${name}/`).toBeTruthy()
    expect(mbSrc, `缺少移动端实现：views-mobile/${name}/`).toBeTruthy()

    const pc = formFields(pcSrc)
    const mb = formFields(mbSrc)
    // 两端都没有表单的页面（纯记录列表）不必登记
    if (pc.size === 0 && mb.size === 0) return

    const keep = (id: string) => !ALLOWED_FIELD_GAPS[`${name}:${id}`]
    const onlyPc = [...pc].filter((id) => keep(id) && !mb.has(id)).sort()
    const onlyMb = [...mb].filter((id) => keep(id) && !pc.has(id)).sort()

    expect(
      { 仅PC有: onlyPc, 仅移动端有: onlyMb },
      `${name} 两端表单字段不一致；补齐缺失的一端（弹窗优先抽成 components/dialogs/ 下的`
        + `共用组件，两端共用一份就不会再漂移），或在 ALLOWED_FIELD_GAPS 里写明原因`
    ).toEqual({ 仅PC有: [], 仅移动端有: [] })
  })
})
