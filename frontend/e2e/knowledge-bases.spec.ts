import { expect, test, type Page } from '@playwright/test'

if (process.env.MEDICAL_RAG_E2E_ISOLATED !== '1') throw new Error('A4 认证及知识库测试必须使用隔离启动器。')
// Disposable fixtures shared with the A3 regression; never the real user's password.
const username = 'mianmian'
const password = 'A3-Isolated-Browser-Password-2026'
let k1 = '', k2 = ''
async function signIn(page: Page) {
  if (new URL(page.url()).pathname !== '/login') await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/app(?:\/|$)/)
}
async function create(page: Page, name: string) {
  await page.getByRole('button', { name: '新建知识库', exact: true }).click()
  await page.getByLabel('知识库名称', { exact: true }).fill(name)
  await page.getByLabel('知识库简介', { exact: true }).fill(`${name} 初始简介`)
  const response = page.waitForResponse(response => response.url().endsWith('/api/knowledge-bases') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '创建知识库', exact: true }).click()
  const created = await response
  expect(created.status()).toBe(201)
  const base = await created.json()
  await expect(page.locator(`[data-library-id="${base.id}"]`)).toBeVisible()
  return String(base.id)
}

test.describe.serial('A4 knowledge bases with real isolated PostgreSQL', () => {
  test.use({ actionTimeout: 10000 })
  test.beforeAll(async ({ request }) => {
    if (!(await request.get('/api/auth/setup-status').then(response => response.json())).initialized) {
      const token = await request.get('/api/auth/csrf').then(response => response.json())
      expect((await request.post('/api/auth/setup', { headers: { 'X-CSRF-TOKEN': token.token }, data: { username, password } })).status()).toBe(201)
    }
  })

  test('create K1/K2, edit, refresh, switch, restart, cancel then confirm current-library deletion', async ({ page, context, request }) => {
    test.setTimeout(90000)
    await signIn(page)
    await page.getByRole('link', { name: '我的知识库', exact: true }).click()
    await expect(page.getByText('还没有知识库，创建第一个研究资料库吧。')).toBeVisible()
    k1 = await create(page, 'A4 K1')
    k2 = await create(page, 'A4 K2')
    console.log('A4：K1/K2 已创建')
    expect(k1).not.toBe(k2)
    const card1 = page.locator(`[data-library-id="${k1}"]`)
    await card1.getByRole('button', { name: '编辑', exact: true }).click()
    await page.getByLabel('知识库名称', { exact: true }).fill('A4 K1 已修改')
    await page.getByLabel('知识库简介', { exact: true }).fill('K1 持久化简介')
    await page.getByRole('button', { name: '保存修改' }).click()
    await expect(card1.getByText('K1 持久化简介', { exact: true })).toBeVisible()
    await expect(page.locator(`[data-library-id="${k2}"]`).getByRole('heading', { name: 'A4 K2', exact: true })).toBeVisible()
    await expect(card1.getByText('文献 0 · 图片 0')).toBeVisible()
    await card1.getByRole('button', { name: '进入知识库' }).click()
    await expect(page.getByRole('heading', { name: 'A4 K1 已修改', exact: true })).toBeVisible()
    await expect(page.locator('.current-library-bar')).toContainText(`ID：${k1}`)
    await page.reload()
    console.log('A4：详情页已刷新')
    await expect(page.locator('.current-library-bar')).toContainText(`ID：${k1}`)
    await expect(page.getByText('当前知识库为空，可以上传 PDF 或 JPG/PNG 原件。')).toBeVisible()
    await page.locator('.current-library-bar .el-select').click()
    await expect(page.getByRole('option', { name: 'A4 K2', exact: true })).toBeVisible()
    await page.screenshot({ path: test.info().outputPath('a4-library-switch.png'), fullPage: true, animations: 'disabled' })
    await page.getByRole('option', { name: 'A4 K2', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/app/knowledge-bases/${k2}$`))
    await expect(page.locator('.current-library-bar')).toContainText(`ID：${k2}`)
    await page.locator('.current-library-bar .el-select').click()
    await page.getByRole('option', { name: 'A4 K1 已修改', exact: true }).click()
    await expect(page.locator('.current-library-bar')).toContainText(`ID：${k1}`)
    // This endpoint belongs to the node runner and can only restart its owned child.
    console.log('A4：知识库切换已验证，准备重启独立后端')
    const restart = await request.post(`${process.env.MEDICAL_RAG_E2E_CONTROL}/restart`, {
      headers: { Authorization: `Bearer ${process.env.MEDICAL_RAG_E2E_CONTROL_TOKEN}` }, timeout: 40000,
    })
    expect(restart.status()).toBe(204)
    console.log('A4：独立后端已重启')
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByText('登录会话已失效，请重新登录。')).toBeVisible()
    await signIn(page)
    await expect(page.locator('.current-library-bar')).toContainText(`ID：${k1}`)
    await expect(page.getByText('K1 持久化简介', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '编辑知识库', exact: true }).click()
    await page.getByLabel('知识库名称', { exact: true }).fill('尚未保存的编辑草稿')
    const revalidated = page.waitForResponse(response => response.url().endsWith(`/api/knowledge-bases/${k1}`) && response.request().method() === 'GET')
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    await revalidated
    await expect(page.getByLabel('知识库名称', { exact: true })).toHaveValue('尚未保存的编辑草稿')
    await page.getByRole('button', { name: '取消', exact: true }).click()
    expect((await context.request.get(`/api/knowledge-bases/${k2}`).then(response => response.json())).name).toBe('A4 K2')
    let deletes = 0
    page.on('request', request => { if (request.method() === 'DELETE' && request.url().includes('/api/knowledge-bases/')) deletes++ })
    await page.getByRole('button', { name: '删除知识库', exact: true }).click()
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await expect(page.getByRole('button', { name: '确认删除', exact: true })).toHaveCount(0)
    expect(deletes).toBe(0)
    expect((await context.request.get(`/api/knowledge-bases/${k1}`)).status()).toBe(200)
    await page.getByRole('button', { name: '删除知识库', exact: true }).click()
    await page.getByRole('button', { name: '确认删除', exact: true }).click()
    await expect(page).toHaveURL(/\/app\/knowledge-bases$/)
    await expect(page.locator('.current-library-bar')).toContainText('尚未选择')
    await expect(page.locator(`[data-library-id="${k1}"]`)).toHaveCount(0)
    await expect(page.locator(`[data-library-id="${k2}"]`)).toBeVisible()
    expect(deletes).toBe(1)
    expect((await context.request.get(`/api/knowledge-bases/${k1}`)).status()).toBe(404)
    await page.screenshot({ path: test.info().outputPath('a4-library-list.png'), fullPage: true, animations: 'disabled' })
  })

  test('private deep links restore after login; missing and illegal IDs never select a library', async ({ page, request }) => {
    await page.goto(`/app/knowledge-bases/${k2}`)
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect((await request.get('/api/knowledge-bases')).status()).toBe(401)
    await signIn(page)
    await expect(page.locator('.current-library-bar')).toContainText(`ID：${k2}`)
    await page.goto('/app/knowledge-bases/9223372036854775807')
    await expect(page.getByText('知识库不存在或无权访问，请返回列表重新选择', { exact: true })).toBeVisible()
    await expect(page.locator('.current-library-bar')).toContainText('尚未选择')
    await page.goto('/app/knowledge-bases/abc')
    await expect(page.getByText('知识库 ID 格式无效，请从列表选择。', { exact: true })).toBeVisible()
    await expect(page.locator('.current-library-bar')).toContainText('尚未选择')
  })

  test('invalid form values give Chinese errors and never send writes', async ({ page }) => {
    await signIn(page)
    await page.goto('/app/knowledge-bases')
    await page.getByRole('button', { name: '新建知识库', exact: true }).click()
    let writes = 0
    page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/knowledge-bases')) writes++ })
    for (const name of ['', '   ', 'x'.repeat(101)]) {
      await page.getByLabel('知识库名称', { exact: true }).fill(name)
      await page.getByRole('button', { name: '创建知识库', exact: true }).click()
      await expect(page.getByText('名称需为 1～100 个字符，不能全为空白或包含控制字符。')).toBeVisible()
    }
    await page.getByLabel('知识库名称', { exact: true }).fill('有效名称')
    await page.getByLabel('知识库简介', { exact: true }).fill('x'.repeat(2001))
    await page.getByRole('button', { name: '创建知识库', exact: true }).click()
    await expect(page.getByText('简介最多 2000 个字符，不能包含非法控制字符。')).toBeVisible()
    expect(writes).toBe(0)
    await page.getByRole('button', { name: '取消', exact: true }).click()
  })

  test('a current library deleted elsewhere is cleared on focus and cannot stay valid', async ({ page, context }) => {
    await signIn(page)
    const csrf = await context.request.get('/api/auth/csrf').then(response => response.json())
    const created = await context.request.post('/api/knowledge-bases', { headers: { 'X-CSRF-TOKEN': csrf.token }, data: { name: '其他窗口删除', description: '' } }).then(response => response.json())
    await page.goto(`/app/knowledge-bases/${created.id}`)
    await expect(page.locator('.current-library-bar')).toContainText(`ID：${created.id}`)
    expect((await context.request.delete(`/api/knowledge-bases/${created.id}`, { headers: { 'X-CSRF-TOKEN': csrf.token } })).status()).toBe(204)
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    await expect(page.getByText('知识库不存在或无权访问，请返回列表重新选择', { exact: true })).toBeVisible()
    await expect(page.locator('.current-library-bar')).toContainText('尚未选择')
  })

  test('list network failure has readable feedback and recovers without fake empty results', async ({ page }) => {
    await signIn(page)
    await page.route('**/api/knowledge-bases', route => route.abort())
    await page.goto('/app/knowledge-bases')
    await expect(page.getByText('无法连接后端或请求超时，请确认后端已启动，然后重新检查。')).toBeVisible()
    await expect(page.getByText('还没有知识库，创建第一个研究资料库吧。')).toHaveCount(0)
    await page.unroute('**/api/knowledge-bases')
    await page.getByRole('button', { name: '刷新列表' }).click()
    await expect(page.locator(`[data-library-id="${k2}"]`)).toBeVisible()
  })

  test('library cards, current selector and dialogs fit ordinary laptop screens', async ({ page }) => {
    await signIn(page)
    await page.goto('/app/knowledge-bases')
    for (const width of [1366, 1024]) {
      await page.setViewportSize({ width, height: 768 })
      await expect(page.locator(`[data-library-id="${k2}"]`)).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await page.getByRole('button', { name: '新建知识库', exact: true }).click()
      await expect(page.getByRole('dialog')).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await page.getByRole('button', { name: '取消', exact: true }).click()
    }
    await expect(page.locator('.pending-navigation')).toHaveCount(4)
  })
})
