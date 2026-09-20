import { test, expect } from '@playwright/test'

test.describe('Login Flow', () => {
  test('should display login page', async ({ page }) => {
    await page.goto('/login')
    await expect(page).toHaveTitle(/登录/)
    await expect(page.locator('input[placeholder="用户名"]')).toBeVisible()
    await expect(page.locator('input[placeholder="密码"]')).toBeVisible()
    await expect(page.locator('text=登 录')).toBeVisible()
  })

  test('should show validation errors for empty form', async ({ page }) => {
    await page.goto('/login')
    await page.locator('text=登 录').click()
    await expect(page.locator('text=请输入用户名')).toBeVisible()
    await expect(page.locator('text=请输入密码')).toBeVisible()
  })

  test('should login successfully with valid credentials', async ({ page }) => {
    await page.goto('/login')
    await page.locator('input[placeholder="用户名"]').fill('admin')
    await page.locator('input[placeholder="密码"]').fill('admin123')
    await page.locator('text=登 录').click()
    await page.waitForURL(/\/dashboard/, { timeout: 15000 })
    await expect(page).toHaveURL(/\/dashboard/)
  })

  test('should show error for invalid credentials', async ({ page }) => {
    await page.goto('/login')
    await page.locator('input[placeholder="用户名"]').fill('admin')
    await page.locator('input[placeholder="密码"]').fill('wrongpassword')
    await page.locator('text=登 录').click()

    // 提示来自后端 message，且只弹一条
    await expect(page.locator('.v-snackbar')).toContainText(/用户名或密码错误/)
    await expect(page.locator('.v-snackbar')).toHaveCount(1)
    // 登录失败不是 token 过期，不该被刷新流程带走
    await expect(page).toHaveURL(/\/login/)
  })

  test('should redirect to root when already logged in', async ({ page }) => {
    await page.goto('/login')
    await page.locator('input[placeholder="用户名"]').fill('admin')
    await page.locator('input[placeholder="密码"]').fill('admin123')
    await page.locator('text=登 录').click()
    await page.waitForURL(/\/dashboard/, { timeout: 15000 })

    await page.goto('/login')
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 10000 })
  })

  test('两个输入框里按回车都能提交，且不是一次整页刷新', async ({ page }) => {
    let loginCalls = 0
    await page.route('**/api/auth/login', async (route) => {
      loginCalls++
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 401, message: '用户名或密码错误', data: null })
      })
    })

    await page.goto('/login')
    await expect(page.locator('input[placeholder="用户名"]')).toBeFocused()

    // 整页刷新会把这个标记抹掉
    await page.evaluate(() => { (window as never as Record<string, unknown>).__osrAlive = true })
    await page.locator('input[placeholder="用户名"]').fill('admin')
    await page.locator('input[placeholder="密码"]').fill('wrongpassword')

    // 表单里有两个文本控件，没有 type="submit" 的按钮时浏览器不做隐式提交 ——
    // 按钮曾经是 @click + 默认的 type="button"，于是在用户名框按回车毫无反应
    await page.locator('input[placeholder="用户名"]').press('Enter')
    await expect.poll(() => loginCalls).toBe(1)

    // VForm 的 onSubmit 在事件未被 preventDefault 时会调 formRef.submit() 走原生提交，
    // 那是一次整页刷新（表单内容进 URL、提示随刷新一起消失）
    expect(await page.evaluate(() => (window as never as Record<string, unknown>).__osrAlive)).toBe(true)
    await expect(page).toHaveURL(/\/login/)

    // 密码框原先靠 @keyup.enter，现在与用户名框一样走原生提交
    await page.locator('input[placeholder="密码"]').press('Enter')
    await expect.poll(() => loginCalls).toBe(2)
  })

  test('密码可显隐切换，切换本身不提交表单', async ({ page }) => {
    let loginCalls = 0
    await page.route('**/api/auth/login', async (route) => {
      loginCalls++
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/login')
    const pwd = page.locator('input[placeholder="密码"]')
    await expect(pwd).toHaveAttribute('type', 'password')
    await page.getByRole('button', { name: '显示密码' }).click()
    await expect(pwd).toHaveAttribute('type', 'text')
    await page.getByRole('button', { name: '隐藏密码' }).click()
    await expect(pwd).toHaveAttribute('type', 'password')
    // 这颗按钮在 <form> 里，VBtn 默认 type="button"，点它不该触发提交
    expect(loginCalls).toBe(0)
  })

  test('系统主题为浅色时，登录面板里的前景色仍是暗色主题那一份', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light' })
    await page.goto('/login')

    // v-theme-provider 不带 with-background 时一个元素都不渲染，而 color 是继承属性：
    // 面板里的文字与图标会从 .v-application(osrLight) 继承成近黑色，压在深色玻璃上几乎看不见。
    // 深色系统的用户看不到这个 bug，所以必须钉在浅色下测
    const avg = await page.locator('input[placeholder="用户名"]').evaluate((el) => {
      const [r, g, b] = getComputedStyle(el).color.match(/\d+/g)!.slice(0, 3).map(Number)
      return (r + g + b) / 3
    })
    expect(avg).toBeGreaterThan(160)
  })
})
