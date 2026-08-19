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
const pcPages = {
  ...import.meta.glob('../../views/openlist/*/index.vue', {
    query: '?raw', import: 'default', eager: true
  }),
  ...import.meta.glob('../../views/system/*/index.vue', {
    query: '?raw', import: 'default', eager: true
  })
} as Record<string, string>

const mobilePages = import.meta.glob('../../views-mobile/*/index.vue', {
  query: '?raw', import: 'default', eager: true
}) as Record<string, string>

/** 有 PC / 移动端两套实现的页面（与 router/index.ts 里 createDeviceView 的清单一致） */
const PAIRS = [
  'strmTask', 'strmRecord', 'copyTask', 'copyRecord',
  'renameTask', 'renameDetail', 'renameOrphan', 'renameConfig',
  'ptIndexer', 'ptDownloader', 'ptMediaServer', 'ptSubscription',
  'ptDownloadRecord', 'ptStatsDashboard', 'ptTorrentBlacklist', 'ptAutoAddRule', 'ptCalendar', 'ptHealth', 'notifyRoute', 'wecomUser'
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
  'openSheet'
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
  const key = Object.keys(map).find((k) => k.endsWith(`/${name}/index.vue`))
  return key ? map[key] : null
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
