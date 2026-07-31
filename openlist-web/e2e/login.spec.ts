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
})
