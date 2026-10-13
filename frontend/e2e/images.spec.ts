import { expect, test, type Page, type APIRequestContext } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
if (process.env.MEDICAL_RAG_E2E_ISOLATED !== '1' || !process.env.MEDICAL_RAG_E2E_FIXTURES) throw new Error('A7 只允许隔离测试。')
const username = 'mianmian', password = 'A3-Isolated-Browser-Password-2026', libraries = new Set<string>()
async function csrf(request: APIRequestContext) { return request.get('/api/auth/csrf').then(response => response.json()) }
async function loginApi(request: APIRequestContext) { const token = await csrf(request); expect((await request.post('/api/auth/login', { headers: { 'X-CSRF-TOKEN': token.token }, form: { username, password } })).status()).toBe(200) }
async function login(page: Page) {
  if (new URL(page.url()).pathname !== '/login') await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill(username); await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page).toHaveURL(/\/app/)
}
async function create(page: Page, name: string) {
  const token = await csrf(page.request); const response = await page.request.post('/api/knowledge-bases', { headers: { 'X-CSRF-TOKEN': token.token }, data: { name, description: 'A7 synthetic fixture' } })
  expect(response.status()).toBe(201); const base = await response.json(); libraries.add(base.id); return base.id as string
}
async function upload(page: Page, base: string, format: 'jpg' | 'png') {
  await page.goto(`/app/knowledge-bases/${base}/images`)
  await expect(page.locator('#image-file')).toBeEnabled()
  const bytes = await readFile(join(process.env.MEDICAL_RAG_E2E_FIXTURES!, `synthetic.${format}`))
  await page.locator('#image-file').setInputFiles({ name: `合成 教学.${format}`, mimeType: format === 'png' ? 'image/png' : 'image/jpeg', buffer: bytes })
  const result = page.waitForResponse(response => response.url().endsWith(`/api/knowledge-bases/${base}/images`) && response.request().method() === 'POST')
  await page.getByRole('button', { name: '开始上传', exact: true }).click(); const response = await result; expect(response.status()).toBe(201)
  const picture = await response.json(); await expect(page.locator(`[data-image-id="${picture.id}"]`)).toBeVisible(); return { picture, bytes }
}
async function originalVisible(page: Page, selector: string) {
  await expect.poll(async () => page.locator(selector).evaluateAll(images => images.map(image => ({ width: (image as HTMLImageElement).naturalWidth, height: (image as HTMLImageElement).naturalHeight })))).toEqual([{ width: 32, height: 24 }])
  const colors = await page.locator(selector).evaluate((image: HTMLImageElement) => {
    const canvas = document.createElement('canvas'); canvas.width = 32; canvas.height = 24; const context = canvas.getContext('2d')!; context.drawImage(image, 0, 0)
    return [context.getImageData(1, 1, 1, 1).data[0], context.getImageData(30, 1, 1, 1).data[0]]
  }); expect(colors[0]).toBeLessThan(80); expect(colors[1]).toBeGreaterThan(200)
}
test.describe.serial('A7 genuine original images', () => {
  test.setTimeout(90000)
  test.use({ actionTimeout: 10000 })
  test.beforeAll(async ({ request }) => {
    if (!(await request.get('/api/auth/setup-status').then(response => response.json())).initialized) { const token = await csrf(request); expect((await request.post('/api/auth/setup', { headers: { 'X-CSRF-TOKEN': token.token }, data: { username, password } })).status()).toBe(201) }
  })
  test.afterEach(async ({ request }) => { await loginApi(request); const token = await csrf(request); for (const id of libraries) expect([204, 404]).toContain((await request.delete(`/api/knowledge-bases/${id}`, { headers: { 'X-CSRF-TOKEN': token.token } })).status()); libraries.clear() })
  test('real JPG/PNG, visible pixels, manual editing, refresh, original hash, switch and confirmed deletion', async ({ page }) => {
    await page.addInitScript(() => {
      const originals = new Set<string>()
      const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL)
      URL.createObjectURL = blob => { const url = create(blob); originals.add(url); return url }
      URL.revokeObjectURL = url => { originals.delete(url); revoke(url) }
      ;(window as unknown as { activeOriginalUrls: Set<string> }).activeOriginalUrls = originals
    })
    await login(page); const k1 = await create(page, 'A7 图片 K1'), k2 = await create(page, 'A7 图片 K2')
    const first = await upload(page, k1, 'jpg'); const second = await upload(page, k1, 'png')
    await originalVisible(page, `[data-image-id="${first.picture.id}"] img`); await originalVisible(page, `[data-image-id="${second.picture.id}"] img`)
    await page.locator(`[data-image-id="${first.picture.id}"]`).getByRole('button', { name: '查看原图及信息' }).click()
    await originalVisible(page, '.image-detail-grid img')
    await page.locator('.image-detail-grid .el-select').click(); await page.getByRole('option', { name: '病灶', exact: true }).click()
    await page.getByLabel('分期', { exact: true }).fill('Stage II'); await page.getByLabel('严重程度', { exact: true }).fill('mild')
    await page.getByLabel('关键词', { exact: true }).fill('anonymous teaching'); await page.getByLabel('备注', { exact: true }).fill('合成图片人工说明')
    await page.getByRole('button', { name: '保存人工信息' }).click(); await expect(page.getByText('人工信息已保存，原图未改写。')).toBeVisible()
    await page.reload(); await expect(page.getByLabel('备注', { exact: true })).toHaveValue('合成图片人工说明'); await originalVisible(page, '.image-detail-grid img')
    const downloaded = page.waitForEvent('download'); await page.getByRole('button', { name: '下载原件', exact: true }).click(); const file = await downloaded
    const bytes = await readFile((await file.path())!); expect(bytes.equals(first.bytes)).toBe(true); expect(createHash('sha256').update(bytes).digest('hex')).toBe(first.picture.sha256)
    await page.locator('.current-library-bar .el-select').click(); await page.getByRole('option', { name: 'A7 图片 K2', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/knowledge-bases/${k2}/images$`)); await expect(page.locator('[data-image-id]')).toHaveCount(0)
    await page.goto(`/app/knowledge-bases/${k1}/images/${first.picture.id}`)
    let deletes = 0; page.on('request', request => { if (request.method() === 'DELETE' && request.url().includes(`/api/images/${first.picture.id}`)) deletes++ })
    await page.getByRole('button', { name: '删除图片', exact: true }).click(); await page.getByRole('button', { name: '取消', exact: true }).click(); expect(deletes).toBe(0)
    expect((await page.request.get(`/api/images/${first.picture.id}`)).status()).toBe(200)
    await page.getByRole('button', { name: '删除图片', exact: true }).click(); await page.getByRole('button', { name: '确认删除', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/knowledge-bases/${k1}/images$`)); await expect(page.locator('[data-image-id]')).toHaveCount(1); expect(deletes).toBe(1)
    expect((await page.request.get(`/api/images/${first.picture.id}/content`)).status()).toBe(404)
    expect((await page.request.get(`/api/knowledge-bases/${k1}`).then(response => response.json())).imageCount).toBe(1)
    await originalVisible(page, `[data-image-id="${second.picture.id}"] img`)
    expect(await page.evaluate(() => (window as unknown as { activeOriginalUrls: Set<string> }).activeOriginalUrls.size)).toBeGreaterThan(0)
    await page.getByRole('link', { name: '我的知识库', exact: true }).click()
    await expect.poll(() => page.evaluate(() => (window as unknown as { activeOriginalUrls: Set<string> }).activeOriginalUrls.size)).toBe(0)
  })
  test('invalid images, sizes, backend failure and forbidden cross-library detail show Chinese errors', async ({ page }) => {
    await login(page); const k1 = await create(page, 'A7 错误 K1'), k2 = await create(page, 'A7 错误 K2'); const { picture } = await upload(page, k1, 'png')
    await page.goto(`/app/knowledge-bases/${k2}/images/${picture.id}`); await expect(page.getByText('图片不属于当前知识库，请返回列表重新选择。')).toBeVisible()
    await page.goto(`/app/knowledge-bases/${k1}/images`); await page.locator('#image-file').setInputFiles({ name: 'fake.png', mimeType: 'image/png', buffer: Buffer.from('not real pixels') })
    await page.getByRole('button', { name: '开始上传', exact: true }).click(); await expect(page.getByText('文件不是支持的 PDF、JPEG 或 PNG 原件')).toBeVisible()
    await page.locator('#image-file').setInputFiles({ name: 'empty.jpg', mimeType: 'image/jpeg', buffer: Buffer.alloc(0) }); await expect(page.getByText('空图片不能上传。')).toBeVisible()
    await page.route('**/api/knowledge-bases/*/images', route => route.abort()); await page.getByRole('button', { name: '刷新图片' }).click(); await expect(page.getByText('无法连接后端或请求超时，请确认后端已启动，然后重新检查。')).toBeVisible(); await page.unroute('**/api/knowledge-bases/*/images')
  })
  test('expired image request redirects to login and releases old object URLs; login recovers', async ({ page }) => {
    await login(page); const base = await create(page, 'A7 会话图片'); const { picture } = await upload(page, base, 'png')
    await page.goto(`/app/knowledge-bases/${base}/images/${picture.id}`); await originalVisible(page, '.image-detail-grid img')
    const token = await csrf(page.request); expect((await page.request.post('/api/auth/logout', { headers: { 'X-CSRF-TOKEN': token.token } })).status()).toBe(204)
    await page.reload(); await expect(page).toHaveURL(/\/login\?redirect=/); await login(page); await originalVisible(page, '.image-detail-grid img')
  })
  test('delayed old-library list cannot populate newly selected image library', async ({ page }) => {
    await login(page); const k1 = await create(page, 'A7 延迟 K1'), k2 = await create(page, 'A7 延迟 K2'); const { picture } = await upload(page, k1, 'png')
    let release!: () => void; const held = new Promise<void>(resolve => { release = resolve })
    await page.route(`**/api/knowledge-bases/${k1}/images`, async route => { const response = await route.fetch(); await held; await route.fulfill({ response }) })
    await page.getByRole('button', { name: '刷新图片' }).click(); await page.locator('.current-library-bar .el-select').click(); await page.getByRole('option', { name: 'A7 延迟 K2', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/knowledge-bases/${k2}/images$`)); release(); await expect(page.locator(`[data-image-id="${picture.id}"]`)).toHaveCount(0); await expect(page.getByText('当前知识库还没有图片，请上传 JPG/PNG 原件。')).toBeVisible()
  })
  test('1024 and 1366 laptop layouts expose original and manual operations without horizontal overflow', async ({ page }) => {
    await login(page); const base = await create(page, 'A7 笔记本图片'); const { picture } = await upload(page, base, 'png')
    for (const width of [1024, 1366]) {
      await page.setViewportSize({ width, height: 768 }); await page.goto(`/app/knowledge-bases/${base}/images/${picture.id}`); await originalVisible(page, '.image-detail-grid img')
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
      await expect(page.getByRole('button', { name: '保存人工信息' })).toBeVisible(); await expect(page.getByRole('button', { name: '下载原件', exact: true })).toBeVisible()
    }
  })
})
