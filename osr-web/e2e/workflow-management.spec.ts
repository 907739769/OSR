import { test, expect, type Page } from '@playwright/test'

test.setTimeout(60000)
test.use({ video: 'off', launchOptions: process.env.OSR_CHROMIUM_PATH ? { executablePath: process.env.OSR_CHROMIUM_PATH } : {} })
const sub = { id: 1, title: '样例剧', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 2, inLibraryCount: 0, inFlightCount: 1, autoSearch: '0', upgradeEnabled: '0' }
async function setup(page: Page, showGuide = false) {
  await page.context().addCookies([{ name: 'token', value: 'workflow-test', url: new URL(test.info().project.use.baseURL || 'http://localhost:3000').origin }])
  if (!showGuide) await page.addInitScript(() => localStorage.setItem('osr-setup-1-dismissed', '1'))
  const state = {
    retryCount: 0, cancelCount: 0, submitted: [] as number[][], copyQueries: [] as URLSearchParams[], downloadQueries: [] as URLSearchParams[],
    jobs: [{ id: 'persisted', kind: 'SUBSCRIPTION_SEARCH', state: 'PARTIAL', cancelRequested: false, createTime: '2026-11-06 12:00:00', items: [
      { targetId: 1, label: '已完成的剧', state: 'SUCCEEDED', message: '已推送 1 个资源' },
      { targetId: 2, label: '未完成的剧', state: 'FAILED', message: '分辨率不符' }
    ] }]
  }
  const menus = [
    ['sub', 'ptSubscription', '订阅管理'], ['copy', 'copyRecord', '同步任务记录'], ['download', 'ptDownloadRecord', '下载记录'],
    ['strm', 'strmRecord', 'STRM 记录'], ['rename', 'renameDetail', '重命名记录'], ['media', 'ptMediaServer', '媒体服务器']
  ].map(([path, component, title]) => ({ path: '/review/' + path, component: `openlist/${component}/index`, meta: { title, icon: 'files' } }))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const url = new URL(route.request().url()), path = url.pathname.replace(/^\/api/, '')
    let data: any = { records: [], total: 0 }
    if (path === '/auth/info') data = { user: { userId: 1, userName: '流程验证' }, roles: ['admin'], permissions: ['*:*:*'] }
    else if (path === '/auth/routers') data = menus
    else if (path === '/openliststrm/background-jobs' && route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      state.submitted.push(body.ids)
      const job = { ...state.jobs[0], id: 'submitted', kind: body.kind, state: 'QUEUED', items: body.ids.map((id: number) => ({ targetId: id, label: '样例剧', state: 'QUEUED', message: '等待执行' })) }
      state.jobs.push(job)
      data = job
    } else if (path === '/openliststrm/background-jobs') data = state.jobs
    else if (path.endsWith('/persisted/retry')) { state.retryCount++; data = state.jobs[0] }
    else if (path.endsWith('/persisted/cancel')) { state.cancelCount++; state.jobs[0].state = 'CANCELLED'; state.jobs[0].cancelRequested = true; data = state.jobs[0] }
    else if (path === '/openliststrm/setup-guide') data = [{ key: 'strm', title: '配置 STRM 源目录', required: true, configured: false, component: 'openlist/strmTask/index', hint: '先执行一个小目录' }]
    else if (path.endsWith('/setup-guide/verify')) data = [{ stage: 'STRM', state: 'SUCCEEDED', message: '已生成样例', component: 'openlist/strmRecord/index', recordKey: 'strmId', recordId: 9 }]
    else if (path === '/openliststrm/pt-subscriptions') data = { records: [sub], total: 1 }
    else if (path.endsWith('/1/progress')) data = { totalEpisodes: 2, missingEpisodes: [1, 2], unairedEpisodes: [], inLibraryCount: 0, inFlightCount: 0 }
    else if (path.endsWith('/1/diagnosis')) data = { subId: 1, title: '样例剧', notes: [], pendingTotal: 1, episodes: [{ episode: 1, label: 'S01E01', state: 'IN_FLIGHT', candidates: 0, accepted: 0, reasons: [], summary: '下载已完成，待检查上传' }] }
    else if (path.endsWith('/1/workflow')) data = [{ episode: 1, steps: [
      { stage: '下载', state: 'COMPLETED', message: '种子已下载完成', component: 'openlist/ptDownloadRecord/index', recordKey: 'recordId', recordId: 7 },
      { stage: '同步', state: 'FAILED', message: '上传失败，可按记录重试', component: 'openlist/copyRecord/index', recordKey: 'copyId', recordId: 9 }
    ] }]
    else if (path === '/openliststrm/copy-records') {
      state.copyQueries.push(url.searchParams)
      const rows = [{ copyId: 9, copySrcFileName: '样例.mkv', copyDstFileName: '样例.mkv', copyStatus: '2', failReason: '上传失败' }, { copyId: 10, copySrcFileName: '其他.mkv', copyDstFileName: '其他.mkv', copyStatus: '3' }]
      data = { records: url.searchParams.has('copyId') ? rows.slice(0, 1) : rows, total: url.searchParams.has('copyId') ? 1 : 2 }
    } else if (path === '/openliststrm/pt-download-records') { state.downloadQueries.push(url.searchParams); data = { records: [], total: 0 } }
    else if (path.includes('/stats')) data = { total: 0 }
    else if (path.endsWith('/owners') || path.endsWith('/space')) data = []
    await route.fulfill({ json: { code: 200, data } })
  })
  return state
}

test('后台任务刷新后仍可查看，失败项可以重新提交', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/review/copy')
  await page.getByRole('button', { name: '后台任务', exact: true }).click()
  const center = page.getByRole('dialog').filter({ hasText: '后台任务' })
  await center.getByRole('button', { name: /订阅补搜/ }).click()
  await expect(center).toContainText('分辨率不符')
  await center.getByRole('button', { name: '只重试未完成或失败项' }).click()
  expect(state.retryCount).toBe(1)
  state.jobs[0].state = 'RUNNING'
  await center.getByRole('button', { name: '刷新', exact: true }).click()
  await center.getByRole('button', { name: '中止任务', exact: true }).click()
  expect(state.cancelCount).toBe(1)
  await page.reload()
  await page.getByRole('button', { name: '后台任务', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('已中止')
})

test('配置引导识别缺项，并按样例返回真实记录入口', async ({ page }) => {
  await setup(page, true)
  await page.goto('/review/copy')
  const guide = page.getByRole('dialog').filter({ hasText: '首次配置引导' })
  await expect(guide).toBeVisible()
  await expect(guide).toContainText('待配置')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await guide.getByLabel('原始视频文件名').fill('样例.mkv')
  await guide.getByRole('button', { name: '查询样例记录' }).click()
  await expect(guide).toContainText('已生成样例')
  await guide.getByRole('button', { name: '查看记录 #9' }).click()
  await expect(page).toHaveURL(/\/review\/strm\?strmId=9/)
})

test('诊断直接定位同步失败记录，清除后回到全部记录', async ({ page, isMobile }) => {
  const state = await setup(page)
  await page.goto('/review/sub')
  await page.locator(isMobile ? '.action-more' : '.more-actions-trigger').first().click()
  await page.getByText('一键诊断', { exact: true }).click()
  const diagnosis = page.getByRole('dialog').filter({ has: page.locator('.diag-title') })
  await expect(diagnosis).toContainText('上传失败，可按记录重试')
  await diagnosis.getByRole('button', { name: '查看记录 #9' }).click()
  await expect(page).toHaveURL(/copy\?copyId=9/)
  await expect(page.getByText('正在查看记录 #9', { exact: false })).toBeVisible()
  await expect.poll(() => state.copyQueries.some(q => q.get('copyId') === '9')).toBe(true)
  await expect(page.getByText('其他.mkv')).toHaveCount(0)
  await page.locator('.v-chip__close').click()
  await expect(page).not.toHaveURL(/copyId=/)
  await expect(page.getByText('其他.mkv').first()).toBeVisible()
  await page.reload()
  await expect(page).not.toHaveURL(/copyId=/)
})


test('整季补齐提交到后台，刷新后任务仍在', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/review/sub')
  await page.getByRole('button', { name: '进度', exact: true }).first().click()
  await page.getByRole('button', { name: /一键补齐/ }).click()
  const center = page.getByRole('dialog').filter({ hasText: '后台任务' })
  await expect(center).toBeVisible()
  expect(state.submitted).toEqual([[1]])
  await page.reload()
  await page.getByRole('button', { name: '后台任务', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('等待执行')
})
