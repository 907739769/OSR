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
    await expect(page.locator('.login-card')).toBeVisible()
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

    await page.locator('.tabbar-item', { hasText: 'STRM记录' }).click()
    await expect(page).toHaveURL(/\/openliststrm\/strm/)
    await page.locator('.tabbar-item', { hasText: '同步记录' }).click()
    await expect(page).toHaveURL(/\/openliststrm\/copy/)

    // 组件从缓存恢复，筛选条件不被重置
    await expect(filter).toHaveValue('MY-FILTER')
    // 但要静默拉一次最新数据，避免看到离开时的旧列表
    await expect.poll(() => listRequests).toBe(2)
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
