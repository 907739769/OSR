import { test, expect, type Page } from '@playwright/test'

// 使用真实页面和可控的接口响应验证离线恢复，不连接数据库或真实下载器。
test.use({ video: 'off', launchOptions: process.env.OSR_CHROMIUM_PATH ? { executablePath: process.env.OSR_CHROMIUM_PATH } : {} })
test.setTimeout(60000)

const recordPages = [
  { key: 'copy', component: 'copyRecord', api: 'copy-records' },
  { key: 'strm', component: 'strmRecord', api: 'strm-records' },
  { key: 'rename', component: 'renameDetail', api: 'rename-details' },
  { key: 'download', component: 'ptDownloadRecord', api: 'pt-download-records' }
]
async function setup(page: Page) {
  const state = { fail: true, records: [] as any[], saved: [] as any[] }
  const menus = [...recordPages.map(p => ({ path: `/review/${p.key}`, component: `openlist/${p.component}/index`, meta: { title: p.key, icon: 'files' } })),
    { path: '/review/downloader', component: 'openlist/ptDownloader/index', meta: { title: '下载器', icon: 'cloud-download' } }]
  const origin = new URL(test.info().project.use.baseURL || 'http://localhost:3000').origin
  await page.context().addCookies([{ name: 'token', value: 'ux-test', url: origin }])
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api/, '')
    let data: any = { records: [], total: 0 }
    if (path === '/auth/info') data = { user: { userId: 1, loginName: 'review', userName: '体验验证' }, roles: ['admin'], permissions: ['*:*:*'] }
    else if (path === '/auth/routers') data = menus
    else if (recordPages.some(p => path === `/openliststrm/${p.api}`)) {
      if (state.fail) { await route.fulfill({ status: 503, json: { message: '测试离线' } }); return }
      data = { records: state.records, total: state.records.length }
    } else if (path.endsWith('/stats')) data = { total: state.records.length }
    else if (path === '/openliststrm/pt-downloaders' && route.request().method() === 'POST') {
      state.saved.push(route.request().postDataJSON()); data = null
    } else if (path.endsWith('/validate-save-path')) data = ''
    else if (path.endsWith('/space') || path.includes('top-subscriptions') || path.endsWith('/trend') || path.includes('recent-failures')) data = []
    else if (path.endsWith('/todo-signals')) data = { offlineDownloaders: [{ name: 'qB 测试', since: null, detail: null }], lowSpaceDownloaders: [], unhealthyMediaServers: [], unresolvedFailedDownloads: 0 }
    else if (path === '/openliststrm/pt-health') data = { bucketCounts: {}, entries: [] }
    await route.fulfill({ json: { code: 200, data } })
  })
  return state
}

for (const record of recordPages) {
  test(`${record.component}: 加载失败不显示空态，重试后恢复`, async ({ page }) => {
    const state = await setup(page)
    await page.goto(`/review/${record.key}`)
    await expect(page.locator('#osr-boot')).toBeHidden({ timeout: 45000 })
    const notice = page.getByRole('alert').filter({ hasText: '记录加载失败' })
    await expect(notice).toBeVisible()
    await expect(page.getByText(/暂无.*记录/)).toHaveCount(0)
    state.fail = false
    await notice.getByRole('button', { name: '重试', exact: true }).click()
    await expect(notice).toHaveCount(0)
    await expect(page.getByText(/暂无.*记录/)).toBeVisible()
  })
}

test('下载器配置保护修改，高级字段折叠时仍校验并正确保存', async ({ page, isMobile }) => {
  const state = await setup(page)
  if (isMobile) await page.setViewportSize({ width: 320, height: 720 })
  await page.goto('/review/downloader')
  await expect(page.locator('#osr-boot')).toBeHidden({ timeout: 45000 })
  await page.getByRole('button', { name: isMobile ? '新增下载器' : '新增', exact: true }).click()
  const dialog = page.getByRole('dialog').filter({ has: page.getByText('新增下载器', { exact: true }) })
  await dialog.getByLabel('名称', { exact: true }).fill('测试下载器')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('downloader-basic.png') })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(dialog.getByLabel('名称', { exact: true })).toHaveValue('测试下载器')
  await dialog.getByLabel('主机', { exact: true }).fill('127.0.0.1')
  await dialog.getByLabel('保存路径', { exact: true }).fill('/downloads')
  const advanced = dialog.getByRole('button', { name: /高级策略/ })
  await expect(dialog.getByLabel('最大并发数', { exact: true })).toBeHidden()
  await advanced.click()
  await dialog.getByLabel('最大并发数', { exact: true }).fill('-1')
  await advanced.click()
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(dialog.getByLabel('最大并发数', { exact: true })).toBeVisible()
  expect(state.saved).toHaveLength(0)
  await dialog.getByLabel('最大并发数', { exact: true }).fill('2')
  await advanced.click()
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(state.saved).toHaveLength(1)
  expect(state.saved[0]).toMatchObject({ name: '测试下载器', maxConcurrent: 2, autoDeleteEnabled: '0', smartClassifyLevel: 'NONE' })
  await expect(page.getByRole('button', { name: '放弃修改', exact: true })).toHaveCount(0)
})

test('首页异常待办位于统计之前', async ({ page, isMobile }) => {
  await setup(page)
  await page.goto('/dashboard')
  await expect(page.locator('#osr-boot')).toBeHidden({ timeout: 45000 })
  const todo = page.locator(isMobile ? '.todo-section' : '.todo-card')
  await expect(todo.getByText('下载器离线', { exact: true })).toBeVisible()
  const stats = page.locator(isMobile ? '.mobile-dashboard > .stats-grid' : '.stat-row').first()
  expect((await todo.boundingBox())!.y).toBeLessThan((await stats.boundingBox())!.y)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('dashboard.png') })
})
