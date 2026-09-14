import { test, expect } from '@playwright/test'

async function login(page: any) {
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/login')
  await page.locator('input[placeholder="用户名"]').fill('admin')
  await page.locator('input[placeholder="密码"]').fill('admin123')
  await page.locator('text=登 录').click()
  await page.waitForURL(/\/dashboard/, { timeout: 15000 })
}

test.describe('Mobile Responsive', () => {
  test('should display login page on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 })
    await page.goto('/login')
    await expect(page).toHaveTitle(/登录/)
    await expect(page.locator('.login-panel')).toBeVisible()
    // 登录页的背景舞台（极光 / 网格 / 暗角）是三层 position:absolute 的满屏装饰，
    // 其中极光层刻意用 inset:-20% 溢出容器以免模糊边缘露出硬边 —— 靠 .login-stage
    // 的 overflow:hidden 兜住。漏掉那一条不会报错，只会让手机上多出一条横向滚动条，
    // 所以在这里钉住：装饰层不该把页面撑宽
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    )
    expect(overflow).toBeLessThanOrEqual(0)
  })

  test('should render the mobile dashboard, not the desktop one', async ({ page }) => {
    await login(page)
    await expect(page.locator('.mobile-dashboard')).toBeVisible()
    // PC 版首页的 echarts 不应出现在移动端
    await expect(page.locator('.echarts-container')).toHaveCount(0)
    // 统计卡：概览 6 张 + 今日处理 3 张 = 9；today 接口慢时今日区可能晚到，接受 >=6
    await expect(page.locator('.stat-card').first()).toBeVisible()
    await expect(page.locator('.stat-card')).toHaveCount(9)
  })

  test('dashboard quick links should all resolve to a real page', async ({ page }) => {
    await login(page)

    // 该用例逐个访问 21 个路由，vite dev 首次编译对应 chunk 可能超过默认 10s 断言窗口，
    // 提高超时以兼容冷编译（产品逻辑本身无需这么久）
    test.setTimeout(240000)
    expect.configure({ timeout: 25000 })

    // 快捷入口来自异步下发的菜单，先等它渲染出来再取数
    await expect(page.locator('.action-item').first()).toBeVisible()
    const count = await page.locator('.action-item').count()
    expect(count).toBeGreaterThan(0)

    for (let i = 0; i < count; i++) {
      await page.goto('/dashboard')
      await expect(page.locator('.action-item')).toHaveCount(count)

      const item = page.locator('.action-item').nth(i)
      const name = (await item.innerText()).trim()
      await item.click()

      // 落地页必须真实存在：既不能停在首页，也不能是 404
      await expect(page, `快捷入口「${name}」未跳转`).not.toHaveURL(/\/dashboard/)
      await expect(page.locator('text=404'), `快捷入口「${name}」指向 404`).toHaveCount(0)
    }
  })

  test('dashboard stat cards should link to their record pages', async ({ page }) => {
    await login(page)

    await page.locator('.stat-card.clickable').first().click()
    await expect(page).toHaveURL(/\/openliststrm\/copy/)
  })

  // 列表页此前每次导航都被强制重挂载，返回时筛选条件、页码、滚动位置全部丢失，
  // 并且要重新打一次接口。
  test('list page should keep filter state when navigating away and back', async ({ page }) => {
    await login(page)

    let listRequests = 0
    page.on('request', r => { if (r.url().includes('/copy-records')) listRequests++ })

    await page.goto('/openliststrm/copy')
    await expect(page.locator('.task-card').first()).toBeVisible()
    expect(listRequests).toBe(1)

    await page.locator('.search-panel-header').click()
    const filter = page.locator('input[placeholder="请输入源目录"]')
    await filter.fill('MY-FILTER')

    // 输入即搜索（useTaskList/useRecordList 里的 300ms 防抖）会自己发一次查询。
    // 这里必须等它落地再离开：否则它可能在导航途中才触发，请求计数变成时序相关，
    // 用例单跑通过、并行跑偶发失败 —— 这条一开始就是这么暴露出来的。
    await expect.poll(() => listRequests).toBe(2)

    await page.locator('.tabbar-item', { hasText: 'STRM记录' }).click()
    await expect(page).toHaveURL(/\/openliststrm\/strm/)
    await page.locator('.tabbar-item', { hasText: '同步记录' }).click()
    await expect(page).toHaveURL(/\/openliststrm\/copy/)

    // 组件从缓存恢复，筛选条件不被重置
    await expect(filter).toHaveValue('MY-FILTER')
    // 但要静默拉一次最新数据，避免看到离开时的旧列表
    await expect.poll(() => listRequests).toBe(3)
  })

  test('dashboard should not be cached', async ({ page }) => {
    await login(page)
    await page.goto('/openliststrm/copy')
    await page.locator('.tabbar-item', { hasText: '首页' }).click()
    await expect(page.locator('.mobile-dashboard')).toBeVisible()
  })

  // 动态路由曾按首次导航时的 device 固化 PC/移动端组件，导致缩放后
  // 「移动端布局里套着 PC 页面」。这里守住双向切换。
  test('page component should follow the viewport, in both directions', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/login')
    await page.locator('input[placeholder="用户名"]').fill('admin')
    await page.locator('input[placeholder="密码"]').fill('admin123')
    await page.locator('text=登 录').click()
    await page.waitForURL(/\/dashboard/, { timeout: 15000 })

    await page.goto('/openliststrm/copy')
    await expect(page.locator('.page-container')).toBeVisible()
    await expect(page.locator('.mobile-page')).toHaveCount(0)

    await page.setViewportSize({ width: 375, height: 812 })
    await expect(page.locator('.mobile-page')).toBeVisible()
    await expect(page.locator('.page-container')).toHaveCount(0)
    await expect(page.locator('.mobile-tabbar')).toBeVisible()

    await page.setViewportSize({ width: 1280, height: 800 })
    await expect(page.locator('.page-container')).toBeVisible()
    await expect(page.locator('.mobile-page')).toHaveCount(0)
    await expect(page.locator('.mobile-tabbar')).toHaveCount(0)
  })
})

/**
 * 页面主动作（新增/立即扫描/保存）并在悬浮底栏右侧，取代原先压在内容上的右下角悬浮按钮。
 * 下面几条坏了都不报错：按钮叠在底栏「更多」上、滚到底盖住分页器、切页后按钮留在下一页、
 * 选择模式下按钮还在。见 composables/useMobilePageAction.ts。
 */
test.describe('Page action beside the tab bar', () => {
  test.beforeEach(async ({ page }) => {
    // 把过渡压到 0.01ms（令牌层的降级），几何断言就不会量到过渡中途的位置
    await page.emulateMedia({ reducedMotion: 'reduce' })
  })

  test('sits in the tab bar row and never over the content', async ({ page }) => {
    await login(page)
    await page.goto('/openliststrm/strm_task')
    const action = page.locator('.mobile-page-action')
    await expect(action).toHaveAttribute('aria-label', '新增STRM任务')

    const bar = (await page.locator('.mobile-tabbar').boundingBox())!
    const btn = (await action.boundingBox())!
    expect(bar.x + bar.width, '底栏没给主动作让位').toBeLessThanOrEqual(btn.x)
    expect(Math.abs(bar.y + bar.height - (btn.y + btn.height)), '主动作与底栏不在同一行').toBeLessThanOrEqual(1)

    // 滚到底：页面内容的底边必须在按钮上沿之上（原先的悬浮按钮正是在这里盖住分页器）
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    const content = (await page.locator('.mobile-page').boundingBox())!
    const btnAtBottom = (await action.boundingBox())!
    expect(content.y + content.height).toBeLessThanOrEqual(btnAtBottom.y)

    await action.click()
    await expect(page.locator('.v-dialog').getByText('新增STRM任务')).toBeVisible()
  })

  test('follows keep-alive navigation instead of leaking to the next page', async ({ page }) => {
    await login(page)
    const action = page.locator('.mobile-page-action')

    await page.goto('/openliststrm/strm_task')
    await expect(action).toBeVisible()

    // STRM 记录页没有主动作：缓存页切走后按钮必须跟着撤掉，底栏还原全宽
    await page.locator('.tabbar-item', { hasText: 'STRM记录' }).click()
    await expect(page).toHaveURL(/\/openliststrm\/strm$/)
    await expect(action).toHaveCount(0)
    await expect(page.locator('.mobile-tabbar')).not.toHaveClass(/mobile-tabbar--with-action/)

    await page.goBack()
    await expect(page).toHaveURL(/\/openliststrm\/strm_task/)
    await expect(action).toHaveAttribute('aria-label', '新增STRM任务')
  })

  test('steps aside while the batch bar owns the tab bar', async ({ page }) => {
    await login(page)
    await page.goto('/openlist/ptSubscription')
    const action = page.locator('.mobile-page-action')
    await expect(action).toHaveAttribute('aria-label', '新增订阅')

    await page.locator('.list-toolbar').getByText('批量操作').click()
    await expect(page.locator('.batch-bar')).toBeVisible()
    await expect(action).toHaveCount(0)

    await page.locator('.batch-bar').getByText('取消', { exact: true }).click()
    await expect(action).toBeVisible()
  })

  test('leaves no horizontal overflow at 320px', async ({ page }) => {
    await login(page)
    await page.setViewportSize({ width: 320, height: 568 })
    await page.goto('/openliststrm/strm_task')
    await expect(page.locator('.mobile-page-action')).toBeVisible()
    const bar = (await page.locator('.mobile-tabbar').boundingBox())!
    const btn = (await page.locator('.mobile-page-action').boundingBox())!
    expect(btn.x + btn.width).toBeLessThanOrEqual(320)
    expect(bar.x + bar.width).toBeLessThanOrEqual(btn.x)
    expect(await horizontalOverflow(page)).toBe(0)
  })
})

/**
 * 「只有一份实现、靠 @media 适配移动端」的页面。
 *
 * 大部分业务页是 views/ + views-mobile/ 两套实现，由 createDeviceView 按 device 分流，
 * 两端的功能差异有 device-parity.spec.ts 盯着。但表单页 / 终端页拆两套不划算——
 * 复制一遍还在正常工作的代码，此后每处改动都要改两遍——所以下面这几个页面只有 PC 一份实现，
 * 在移动端靠 @media (max-width: 768px) 适配。
 *
 * 代价是它们落在两张网之间：device-parity.spec.ts 比对的是成对页面的 composable 动作集合，
 * 无对可比就不检查；而「PC 页在窄屏下还能不能用」本身没有任何自动化护栏。
 * 谁往里加一个宽表格、一个写死的 min-width，CI 全绿，只有掏出手机才发现。
 * 这里补上那张网。
 *
 * 断言三件事，缺一不可：
 *   1. ready 选择器可见——路由 path 漂了会落到 404，而 404 页面同样不横向溢出，
 *      只测溢出等于给自己发一张永远通过的免检证（pages.spec.ts 顶部记着这个教训）。
 *   2. .mobile-tabbar 存在——确认页面真的渲染在 MobileLayout 里，而不是 PC 布局缩小版。
 *   3. 无横向溢出——窄屏最典型的坏法：整页能左右拖，右边的按钮点不到。
 */
const RESPONSIVE_ONLY_PAGES = [
  // 两个 PT 配置页都是 .filter-form + .dimension-list，光用结构选择器认不出彼此，
  // 万一路由串了也测不出来；这里改用各自独有的分节标题
  { title: 'PT 过滤规则', path: '/openlist/ptFilterConfig', ready: '.section-divider:has-text("硬性过滤")' },
  { title: 'PT 洗版规则', path: '/openlist/ptUpgradeConfig', ready: '.section-divider:has-text("目标质量")' },
  { title: '参数设置', path: '/system/config', ready: '.section-cards .config-item' },
  // .mobile-card 只在 <768px 渲染，它可见本身就说明表格降级生效了（任务由初始化 SQL 预置，恒非空）
  { title: '定时任务', path: '/monitor/job', ready: '.mobile-card' },
  { title: '实时日志', path: '/monitor/log', ready: '.realtime-log-container .log-content' }
]

/** documentElement 的横向溢出像素数，0 表示不出现横向滚动条 */
async function horizontalOverflow(page: any): Promise<number> {
  return page.evaluate(() => {
    const de = document.documentElement
    return de.scrollWidth - de.clientWidth
  })
}

test.describe('Responsive-only pages', () => {
  test('should render inside the mobile layout without horizontal overflow', async ({ page }) => {
    await login(page)
    test.setTimeout(120000)

    for (const { title, path, ready } of RESPONSIVE_ONLY_PAGES) {
      await page.goto(path)
      await expect(page.locator(ready).first(), `「${title}」没渲染出内容（路由改过？）`).toBeVisible()
      await expect(page.locator('.mobile-tabbar'), `「${title}」没落在 MobileLayout 里`).toBeVisible()
      expect(await horizontalOverflow(page), `「${title}」出现横向溢出`).toBe(0)
    }
  })

  // 320px 是仍在流通的最窄机型（iPhone SE 一代）。768px 的媒体查询在 375 上成立，
  // 不代表在 320 上也成立——写死宽度的元素正是在这最后 55px 里露出来的。
  test('should survive the narrowest phone width (320px)', async ({ page }) => {
    await login(page)
    await page.setViewportSize({ width: 320, height: 568 })
    test.setTimeout(120000)

    for (const { title, path, ready } of RESPONSIVE_ONLY_PAGES) {
      await page.goto(path)
      await expect(page.locator(ready).first(), `「${title}」在 320px 下没渲染出内容`).toBeVisible()
      expect(await horizontalOverflow(page), `「${title}」在 320px 下出现横向溢出`).toBe(0)
    }
  })
})
