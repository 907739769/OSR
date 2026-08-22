import { describe, it, expect } from 'vitest'
import vuetify from '@/plugins/vuetify'

/**
 * 主题前景/背景对比度断言。
 *
 * 挡的是这样一类改动：调色板里改了某个 `X`，却没有同时改配套的 `on-X`。
 * 这种错在开发时几乎不会被发现——页面照常渲染、控制台一声不吭，只是某个组件上的字
 * 变得看不清，而那个组件往往是 tooltip、snackbar 这种平时不常展开的浮层。
 *
 * 真实事故：`surface-variant` 被改成浅米色 `#EDE7DD` 用作面板变体色，但 `on-surface-variant`
 * 没跟着改。自定义主题会与 Vuetify 默认主题 deep merge（`parseThemeOptions`），于是它
 * 继承了默认 light 主题的 `#EEEEEE`——浅米底配近白字，对比度 1.06:1。而 `v-tooltip` 的
 * 底色与字色<直接>就是这一对变量，全站 tooltip 的文字因此都是不可读的。
 * 暗色主题坏得更彻底（深蓝灰底 + 继承来的纯黑字，1.7:1），只是没人在暗色下展开过 tooltip。
 *
 * 阈值取 WCAG 2.1 AA 的正文标准 4.5:1。tooltip 的字号是 0.875rem，够不上「大文本」那档
 * 放宽到 3:1 的条件，所以这里不给例外。
 */

/** sRGB 相对亮度（WCAG 2.1 定义） */
function relativeLuminance(hex: string): number {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) throw new Error(`不是 6 位十六进制颜色: ${hex}`)
  const int = parseInt(m[1], 16)
  const channels = [(int >> 16) & 0xff, (int >> 8) & 0xff, int & 0xff]
  const [r, g, b] = channels.map((c) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrastRatio(fg: string, bg: string): number {
  const l1 = relativeLuminance(fg)
  const l2 = relativeLuminance(bg)
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1]
  return (hi + 0.05) / (lo + 0.05)
}

const MIN_RATIO = 4.5

/**
 * 已知未达 AA 的既有配色，登记在案而不是放过。
 *
 * 这两条是主题建立时就存在的，与 surface-variant 那次事故无关：白字落在琥珀主色
 * 与绿色成功色上，差着 0.3~0.5 的量。要修就得动品牌色本身（或把按钮文字改成深色），
 * 那是一次全站观感变更，不该顺手夹在一个 bug 修复里做——所以这里只钉住「不许更差」。
 *
 * 值取当前实测值向下留 0.05 的余量：有人把主色调得更暗一点（对比度上升）不会误报，
 * 而调得更亮（对比度下降）会立刻红。真正修好之后请把对应条目<删掉>，让它回到 4.5 的红线。
 */
const KNOWN_BELOW_AA: Record<string, number> = {
  // #FFFFFF on #B4690E = 4.23:1
  'osrLight.primary': 4.2,
  // #FFFFFF on #3F8F5F = 3.96:1
  'osrLight.success': 3.9,
}

/**
 * 只检查本项目<自己覆盖过>的颜色。
 *
 * Vuetify 默认主题里还有一批 on-* 配对（surface-bright、theme-code 等），
 * 它们的取值不归本项目管，一并检查等于把上游的选择也纳入本仓库的红线——
 * 上游调一次色这个测试就红一次，而那既不是本项目的 bug 也无处可修。
 */
const OWNED_PAIRS = [
  'primary',
  'secondary',
  'error',
  'success',
  'warning',
  'info',
  'background',
  'surface',
  'surface-variant',
] as const

describe('主题配色对比度', () => {
  const themes = vuetify.theme.themes.value

  it('osrLight / osrDark 两个主题都存在', () => {
    expect(Object.keys(themes)).toEqual(expect.arrayContaining(['osrLight', 'osrDark']))
  })

  for (const themeName of ['osrLight', 'osrDark']) {
    describe(themeName, () => {
      for (const key of OWNED_PAIRS) {
        const floor = KNOWN_BELOW_AA[`${themeName}.${key}`] ?? MIN_RATIO
        const title = floor === MIN_RATIO
          ? `${key} 与 on-${key} 的对比度不低于 ${MIN_RATIO}:1`
          : `${key} 与 on-${key} 的对比度不低于 ${floor}:1（已知未达 AA，仅钉住不再更差）`
        it(title, () => {
          const colors = themes[themeName].colors as Record<string, string>
          const bg = colors[key]
          const fg = colors[`on-${key}`]

          // 缺了 on-X 本身就是 bug：deep merge 会让它静默继承上游的值，
          // 而上游那个值是配上游的 X 的，与本项目改过的 X 没有任何关系。
          expect(bg, `${themeName} 缺少颜色 ${key}`).toBeTruthy()
          expect(fg, `${themeName} 缺少配套的 on-${key}`).toBeTruthy()

          const ratio = contrastRatio(fg, bg)
          expect(
            ratio,
            `${themeName}.${key}: 前景 ${fg} 落在背景 ${bg} 上，对比度仅 ${ratio.toFixed(2)}:1`
          ).toBeGreaterThanOrEqual(floor)
        })
      }
    })
  }

  it('对比度算法本身可信（用 WCAG 的两个极值校准）', () => {
    // 纯黑配纯白是 21:1，同色是 1:1——算错了这两个数一定不对
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 1)
    expect(contrastRatio('#777777', '#777777')).toBeCloseTo(1, 5)
  })
})
