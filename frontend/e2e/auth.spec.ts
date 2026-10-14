import { expect, test, type Page } from '@playwright/test'

if (process.env.MEDICAL_RAG_E2E_ISOLATED !== '1') {
  throw new Error('认证测试必须由 npm run test:e2e 的隔离启动器运行，禁止对现有数据库初始化。')
}

const username = 'mianmian' // Same name as an existing-admin scenario, only in the disposable database.
const password = 'A3-Isolated-Browser-Password-2026'
async function signIn(page: Page) {
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/app$/)
}

test.describe.serial('A3 actual authentication flow on an isolated database', () => {
  test('first setup, login, refresh, logout and login again; profiles and cookies are safe', async ({ page, context }) => {
    const profiles: Record<string, unknown>[] = []
    page.on('response', async (response) => {
      if (/\/api\/auth\/(setup|login|me)$/.test(response.url()) && response.status() < 300) {
        profiles.push(await response.json())
      }
    })
    await page.goto('/')
    await expect(page).toHaveURL(/\/setup$/)
    await page.screenshot({ path: test.info().outputPath('a3-setup.png'), fullPage: true, animations: 'disabled' })
    await page.getByLabel('管理员用户名', { exact: true }).fill(username)
    await page.getByLabel('管理员密码', { exact: true }).fill(password)
    await page.getByLabel('确认密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '创建管理员', exact: true }).click()
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByText('管理员创建成功，请使用刚设置的账号登录。')).toBeVisible()
    await page.screenshot({ path: test.info().outputPath('a3-login.png'), fullPage: true, animations: 'disabled' })
    expect((await context.request.get('/api/auth/me')).status()).toBe(401)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/app$/)
    await expect(page.getByRole('heading', { name: `欢迎，${username}` })).toBeVisible()
    await page.screenshot({ path: test.info().outputPath('a3-home.png'), fullPage: true, animations: 'disabled' })
    const csrf = await context.request.get('/api/auth/csrf').then((response) => response.json())
    expect((await context.request.post('/api/auth/logout', { headers: { 'X-CSRF-TOKEN': 'invalid' } })).status()).toBe(403)
    expect(csrf.token).toBeTruthy()
    const cookie = (await context.cookies()).find((item) => item.name === 'JSESSIONID')
    expect(cookie?.httpOnly).toBe(true)
    expect(cookie?.sameSite).toBe('Lax')
    expect(await page.evaluate(() => document.cookie)).not.toContain('JSESSIONID')
    await page.reload()
    await expect(page.getByRole('heading', { name: `欢迎，${username}` })).toBeVisible()
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login/)
    expect((await context.request.get('/api/auth/me')).status()).toBe(401)
    await signIn(page)
    expect(profiles.length).toBeGreaterThan(3)
    for (const profile of profiles) expect(Object.keys(profile).sort()).toEqual(['createdAt', 'id', 'username'])
    expect(await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) })))
      .toEqual({ local: [], session: [] })
  })

  test('existing administrator skips setup; private deep link and direct API remain protected', async ({ page, request }) => {
    await page.goto('/setup')
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByRole('heading', { name: '创建本地管理员' })).toHaveCount(0)
    await page.goto('/app/health')
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect((await request.get('/api/auth/me')).status()).toBe(401)
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/app\/health$/)
    await expect(page.getByText('工程启动成功：后端、数据库、扩展及迁移均正常。')).toBeVisible()
  })

  test('wrong password gives Chinese feedback and never grants access', async ({ page, context }) => {
    await page.goto('/login')
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill('incorrect-isolated-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByText('账号或密码错误')).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
    expect((await context.request.get('/api/auth/me')).status()).toBe(401)
    await expect(page.getByLabel('密码', { exact: true })).toHaveValue('')
  })

  test('initial backend outage displays recovery page and retries actual backend', async ({ page }) => {
    await page.route('**/api/auth/setup-status', (route) => route.abort())
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '暂时无法连接本机服务' })).toBeVisible()
    await expect(page.getByText('无法连接后端或请求超时，请确认后端已启动，然后重新检查。')).toBeVisible()
    await page.unroute('**/api/auth/setup-status')
    await page.getByRole('button', { name: '重新连接' }).click()
    await expect(page).toHaveURL(/\/login/)
  })

  test('login network failure shows an actionable message', async ({ page }) => {
    await page.goto('/login')
    await page.route('**/api/auth/login', (route) => route.abort())
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByText('无法连接后端或请求超时，请确认后端已启动，然后重新检查。')).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
  })

  test('server-invalidated session returns to login with an expiry message', async ({ page, context }) => {
    await signIn(page)
    const csrf = await context.request.get('/api/auth/csrf').then((response) => response.json())
    expect((await context.request.post('/api/auth/logout', { headers: { 'X-CSRF-TOKEN': csrf.token } })).status()).toBe(204)
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByText('登录会话已失效，请重新登录。')).toBeVisible()
  })

  test('laptop layout fits and future modules are disabled; unsafe return URLs are ignored', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await page.goto('/login?redirect=https%3A%2F%2Fexample.com')
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/app$/)
    await expect(page.getByRole('navigation', { name: '系统导航' })).toBeVisible()
    await expect(page.locator('.pending-navigation')).toHaveCount(3)
    for (const item of await page.locator('.pending-navigation').all()) await expect(item).toHaveAttribute('aria-disabled', 'true')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.setViewportSize({ width: 1024, height: 768 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
  })

  test('actual server idle timeout returns to login without background keepalive', async ({ page, context }) => {
    test.setTimeout(90000)
    await signIn(page)
    // The disposable backend uses a one-minute timeout; the user's backend stays at 30 minutes.
    await page.waitForTimeout(35000)
    await page.waitForTimeout(30000)
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByText('登录会话已失效，请重新登录。')).toBeVisible()
    expect((await context.request.get('/api/auth/me')).status()).toBe(401)
  })
})
