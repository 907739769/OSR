import { describe, it, expect } from 'vitest'
import { createMobileChromeState } from '@/composables/useMobileChrome'

/**
 * 移动端外壳的两个滚动状态。这里钉住的核心是**它们的判据不同**：
 * `scrolled` 看绝对位置（大标题还看得见吗），`compact` 看滚动方向（用户在往下读吗）。
 *
 * 最值得守的是「向上滚要能把底栏还原」那条：按绝对位置做的话，任何一个滚过一次的
 * 列表页此后永远是图标态，等于把文字标签删了——而那正是新用户分得清这五格的唯一依据。
 * 这个退化没有任何报错，只是标签再也不出现。
 */
describe('useMobileChrome 的滚动判据', () => {
  it('大标题滚出视口后 scrolled 才为真', () => {
    const s = createMobileChromeState()
    expect(s.scrolled.value).toBe(false)

    s.update(20)
    expect(s.scrolled.value).toBe(false)

    s.update(200)
    expect(s.scrolled.value).toBe(true)

    s.update(0)
    expect(s.scrolled.value).toBe(false)
  })

  it('向下滚收起底栏，向上滚还原——判据是方向不是「滚过没有」', () => {
    const s = createMobileChromeState()

    s.update(400)
    expect(s.compact.value).toBe(true)

    // 仍在页面深处，但方向反了：必须还原，否则标签一去不返
    s.update(300)
    expect(s.compact.value).toBe(false)

    s.update(500)
    expect(s.compact.value).toBe(true)
  })

  it('回到顶部附近一律展开，不管最后几帧是往哪个方向动的', () => {
    const s = createMobileChromeState()
    s.update(400)
    expect(s.compact.value).toBe(true)

    // 快速上滑到顶：最后一段位移可能不足方向阈值，没有这条兜底就会停在收窄态
    s.update(30)
    expect(s.compact.value).toBe(false)
  })

  it('小于方向阈值的抖动不翻转底栏，且抖动不会逐步累积', () => {
    const s = createMobileChromeState()
    s.update(400)
    s.update(300)
    expect(s.compact.value).toBe(false)

    // 在 300 附近来回抖，每次位移都不到 8px
    for (let i = 0; i < 20; i++) s.update(i % 2 ? 305 : 300)
    expect(s.compact.value).toBe(false)
  })

  it('慢速连续滚动能累积到阈值并触发', () => {
    const s = createMobileChromeState()
    s.update(300)
    for (let y = 303; y <= 330; y += 3) s.update(y)
    expect(s.compact.value).toBe(true)
  })

  it('reset 让换页后的顶栏不带着上一页的状态', () => {
    const s = createMobileChromeState()
    s.update(400)
    expect(s.scrolled.value).toBe(true)
    expect(s.compact.value).toBe(true)

    s.reset()
    expect(s.scrolled.value).toBe(false)
    expect(s.compact.value).toBe(false)

    // reset 之后紧接着向下滚，方向基准要从 0 重新算起
    s.update(400)
    expect(s.compact.value).toBe(true)
  })

  it('负的滚动位置（iOS 橡皮筋回弹）按顶部处理', () => {
    const s = createMobileChromeState()
    s.update(400)
    s.update(-60)
    expect(s.scrolled.value).toBe(false)
    expect(s.compact.value).toBe(false)
  })
})
