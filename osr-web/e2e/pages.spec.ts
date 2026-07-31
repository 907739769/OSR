import { test, expect } from '@playwright/test'

/**
 * 与 sys_menu 中 C 类型菜单的 url 保持一致（见 ruoyi-common/src/main/resources/sql）。
 * 此前这里列的是 /system/user、/monitor/server、/openlist/strm-task 等旧模板里的
 * 页面，本项目既没有对应菜单也没有对应组件；加上每个用例都用
 * isVisible().catch(() => false) 包着，找不到就静默跳过，因此全部永远通过。
 */
const PAGES = [
  { title: '字典管理', path: '/system/dict' },
  { title: '参数设置', path: '/system/config' },
  { title: '定时任务', path: '/monitor/job' },
  { title: '实时日志', path: '/monitor/log' },
  { title: '同步任务配置', path: '/openliststrm/task' },
  { title: '同步任务记录', path: '/openliststrm/copy' },
  { title: 'strm任务配置', path: '/openliststrm/strm_task' },
  { title: 'STRM生成记录', path: '/openliststrm/strm' },
  { title: '重命名任务配置', path: '/openliststrm/renameTask' },
  { title: '重命名明细', path: '/openliststrm/renameDetail' }
]

test.beforeEach(async ({ page }) => {
  await page.goto('/login')
  await page.locator('input[placeholder="用户名"]').fill('admin')
  await page.locator('input[placeholder="密码"]').fill('admin123')
  await page.locator('text=登 录').click()
  await page.waitForURL(/\/dashboard/, { timeout: 15000 })
})

test.describe('Dashboard', () => {
  test('should load dashboard', async ({ page }) => {
    await expect(page).toHaveURL(/\/dashboard/)
    // 首页按设备分流成两套实现，断言当前设备对应的那一套渲染出来即可
    await expect(page.locator('.dashboard, .mobile-dashboard')).toBeVisible()
  })
})

test.describe('Menu Pages', () => {
  for (const { title, path } of PAGES) {
    test(`should render ${title} (${path})`, async ({ page }) => {
      await page.goto(path)

      await expect(page).toHaveURL(new RegExp(path.replace(/\//g, '\\/')))
      // 标题由路由守卫按菜单 meta.title 设置，能对上说明路由确实匹配到了这个菜单
      await expect(page).toHaveTitle(new RegExp(title))
      // componentMap 查不到组件时会回落到 404 视图（此时 meta.title 仍是菜单名，
      // 光看标题发现不了），所以要单独确认渲染的不是 404
      await expect(page.locator('.error-page')).toHaveCount(0)
    })
  }
})
