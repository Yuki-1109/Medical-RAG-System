import { expect, test, type Page, type APIRequestContext } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFile, mkdtemp, open, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { syntheticPdf } from './synthetic-pdf'
if (process.env.MEDICAL_RAG_E2E_ISOLATED !== '1') throw new Error('A6 必须使用隔离测试启动器。')
const username = 'mianmian', password = 'A3-Isolated-Browser-Password-2026'
const libraries = new Set<string>()
const fixture = syntheticPdf(), filename = '合成 English research.pdf'
async function signIn(page: Page) {
  if (new URL(page.url()).pathname !== '/login') await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill(username); await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page).toHaveURL(/\/app(?:\/|$)/)
}
async function signInApi(request: APIRequestContext) {
  const csrf = await request.get('/api/auth/csrf').then(response => response.json())
  expect((await request.post('/api/auth/login', { headers: { 'X-CSRF-TOKEN': csrf.token }, form: { username, password } })).status()).toBe(200)
  return request.get('/api/auth/csrf').then(response => response.json())
}
async function create(page: Page, name: string) {
  const token = await page.request.get('/api/auth/csrf').then(response => response.json())
  const response = await page.request.post('/api/knowledge-bases', { headers: { 'X-CSRF-TOKEN': token.token }, data: { name, description: 'A6 synthetic isolated fixture' } })
  expect(response.status()).toBe(201); const base = await response.json(); libraries.add(base.id); return String(base.id)
}
async function upload(page: Page, base: string, name = filename) {
  await page.goto(`/app/knowledge-bases/${base}/documents`)
  await expect(page.getByText('当前知识库还没有文献，请上传 PDF 原件。')).toBeVisible()
  await page.locator('#pdf-file').setInputFiles({ name, mimeType: 'application/pdf', buffer: fixture })
  const result = page.waitForResponse(response => response.url().endsWith(`/api/knowledge-bases/${base}/documents`) && response.request().method() === 'POST')
  await page.getByRole('button', { name: '开始上传', exact: true }).click()
  const response = await result; expect(response.status()).toBe(201); const document = await response.json()
  await expect(page.locator(`[data-document-id="${document.id}"]`)).toBeVisible(); return document
}
async function rendered(page: Page, number: number) {
  const canvas = page.locator(`canvas[data-rendered-page="${number}"]`)
  await expect(canvas).toBeVisible({ timeout: 20000 })
  const pixels = await canvas.evaluate((element: HTMLCanvasElement) => {
    const data = element.getContext('2d')!.getImageData(0, 0, element.width, element.height).data
    let colored = 0, sum = 0
    for (let i = 0; i < data.length; i += 4) { if (data[i]! < 240 || data[i + 1]! < 240 || data[i + 2]! < 240) colored++; sum = (sum + data[i]! + 3 * data[i + 1]! + 7 * data[i + 2]!) % 1000000007 }
    return { colored, sum, width: element.width }
  })
  expect(pixels.width).toBeGreaterThan(200); expect(pixels.colored).toBeGreaterThan(1000)
  return pixels.sum
}
async function download(page: Page) {
  const result = page.waitForEvent('download'); await page.getByRole('button', { name: '下载原件', exact: true }).click()
  const saved = await result; expect(saved.suggestedFilename()).toBe(filename)
  const bytes = await readFile((await saved.path())!); expect(bytes.equals(fixture)).toBe(true)
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(createHash('sha256').update(fixture).digest('hex'))
}

test.describe.serial('A6 actual PDF documents', () => {
  test.use({ actionTimeout: 10000 })
  test.beforeAll(async ({ request }) => {
    if (!(await request.get('/api/auth/setup-status').then(response => response.json())).initialized) {
      const token = await request.get('/api/auth/csrf').then(response => response.json())
      expect((await request.post('/api/auth/setup', { headers: { 'X-CSRF-TOKEN': token.token }, data: { username, password } })).status()).toBe(201)
    }
  })
  test.afterEach(async ({ request }) => {
    const token = await signInApi(request)
    for (const id of libraries) {
      const response = await request.delete(`/api/knowledge-bases/${id}`, { headers: { 'X-CSRF-TOKEN': token.token } })
      expect([204, 404]).toContain(response.status())
    }
    libraries.clear() // Only our synthetic test libraries; never delete arbitrary existing data.
  })
  test('real PDF upload, pages, deep links, exact download, refresh/restart, cancellation and nonempty deletion', async ({ page, request }) => {
    test.setTimeout(120000)
    await signIn(page); const k1 = await create(page, 'A6 专用 K1'), k2 = await create(page, 'A6 专用 K2')
    const first = await upload(page, k1)
    expect(first.pageCount).toBe(3); expect(first.processingStatus).toBe('PENDING')
    await expect(page.locator(`[data-document-id="${first.id}"]`)).toContainText('3 页')
    await expect(page.locator(`[data-document-id="${first.id}"]`)).toContainText('待处理')
    await page.getByRole('button', { name: '查看原文', exact: true }).click()
    const firstPixels = await rendered(page, 1)
    await page.getByRole('button', { name: '下一页', exact: true }).click(); expect(await rendered(page, 2)).not.toBe(firstPixels)
    await page.getByRole('spinbutton', { name: '跳转页码' }).fill('3'); await page.getByRole('button', { name: '跳转', exact: true }).click(); await rendered(page, 3)
    await download(page); await page.reload(); await rendered(page, 3)
    const restart = await request.post(`${process.env.MEDICAL_RAG_E2E_CONTROL}/restart`, { headers: { Authorization: `Bearer ${process.env.MEDICAL_RAG_E2E_CONTROL_TOKEN}` }, timeout: 40000 })
    expect(restart.status()).toBe(204); await page.evaluate(() => window.dispatchEvent(new Event('focus'))); await expect(page).toHaveURL(/\/login\?redirect=/)
    await signIn(page); await rendered(page, 3); await download(page)
    await page.goto(`/app/knowledge-bases/${k1}/documents/${first.id}?page=999`)
    await expect(page.getByText('页码无效，请输入 1～3 的整数。')).toBeVisible(); await rendered(page, 1)
    await page.goto(`/app/knowledge-bases/${k1}/documents`)
    await page.locator('#pdf-file').setInputFiles({ name: filename, mimeType: 'application/pdf', buffer: fixture })
    const response = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/documents'))
    await page.getByRole('button', { name: '开始上传', exact: true }).click(); const second = await (await response).json()
    expect(second.id).not.toBe(first.id); await expect(page.locator('.document-row')).toHaveCount(2)
    await page.locator('.current-library-bar .el-select').click(); await page.getByRole('option', { name: 'A6 专用 K2', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/knowledge-bases/${k2}/documents$`)); await expect(page.locator('.document-row')).toHaveCount(0)
    const other = await upload(page, k2)
    await page.goto(`/app/knowledge-bases/${k2}/documents/${first.id}`); await expect(page.getByText('文献不属于当前知识库，请返回列表重新选择。')).toBeVisible()
    await page.goto(`/app/knowledge-bases/${k1}/documents`)
    let deletes = 0; page.on('request', request => { if (request.method() === 'DELETE') deletes++ })
    const row = page.locator(`[data-document-id="${first.id}"]`)
    await row.getByRole('button', { name: '删除文献', exact: true }).click(); await page.getByRole('button', { name: '取消', exact: true }).click(); expect(deletes).toBe(0); await expect(row).toBeVisible()
    await row.getByRole('button', { name: '删除文献', exact: true }).click(); await page.getByRole('button', { name: '确认删除', exact: true }).click(); await expect(row).toHaveCount(0)
    expect((await page.request.get(`/api/documents/${first.id}/content`)).status()).toBe(404)
    expect((await page.request.get(`/api/knowledge-bases/${k1}`).then(response => response.json())).documentCount).toBe(1)
    await page.goto(`/app/knowledge-bases/${k1}`); await page.getByRole('button', { name: '删除知识库', exact: true }).click()
    await expect(page.getByRole('dialog')).toContainText('1 篇有效文献'); await page.getByRole('button', { name: '取消', exact: true }).click(); expect(deletes).toBe(1)
    await page.getByRole('button', { name: '删除知识库', exact: true }).click(); await page.getByRole('button', { name: '确认删除', exact: true }).click()
    await expect(page).toHaveURL(/\/app\/knowledge-bases$/); libraries.delete(k1)
    expect((await page.request.get(`/api/documents/${second.id}/download`)).status()).toBe(404)
    expect((await page.request.get(`/api/documents/${other.id}/download`)).status()).toBe(200)
    await expect(page.locator(`[data-library-id="${k2}"]`)).toContainText('文献 1 · 图片 0')
    await page.goto(`/app/knowledge-bases/${k2}/documents/${other.id}?page=2`); await rendered(page, 2); await download(page)
  })
  test('invalid uploads report actual Chinese errors without inserting fake success cards', async ({ page }) => {
    await signIn(page); const base = await create(page, 'A6 无效上传'); await page.goto(`/app/knowledge-bases/${base}/documents`)
    let posts = 0; page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/documents')) posts++ })
    await expect(page.getByText('当前知识库还没有文献，请上传 PDF 原件。')).toBeVisible()
    await page.locator('#pdf-file').setInputFiles({ name: 'empty.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(0) }); await expect(page.getByText('空文件不能上传。')).toBeVisible(); expect(posts).toBe(0)
    await page.locator('#pdf-file').setInputFiles({ name: 'fake.pdf', mimeType: 'application/pdf', buffer: Buffer.from('fake PDF') }); await page.getByRole('button', { name: '开始上传', exact: true }).click()
    await expect(page.getByText('文件不是支持的 PDF、JPEG 或 PNG 原件')).toBeVisible(); await expect(page.locator('.document-row')).toHaveCount(0)
    expect((await page.request.get(`/api/knowledge-bases/${base}`).then(response => response.json())).documentCount).toBe(0)
    const directory = await mkdtemp(join(tmpdir(), 'medical-rag-a6-oversize-')), oversized = join(directory, 'too-large.pdf')
    try {
      const file = await open(oversized, 'wx'); try { await file.truncate(52428801) } finally { await file.close() }
      await page.locator('#pdf-file').setInputFiles(oversized); await expect(page.getByText('PDF 超过大小上限（50.0 MiB）。')).toBeVisible(); expect(posts).toBe(1)
    } finally { await page.locator('#pdf-file').setInputFiles([]); await rm(directory, { recursive: true }) }
  })
  test('list and preview network errors are readable and recover without fake data', async ({ page }) => {
    await signIn(page); const base = await create(page, 'A6 连接故障'); const document = await upload(page, base)
    await page.route(`**/api/knowledge-bases/${base}/documents`, route => route.abort())
    await page.reload(); await expect(page.getByText('无法连接后端或请求超时，请确认后端已启动，然后重新检查。')).toBeVisible()
    await page.unroute(`**/api/knowledge-bases/${base}/documents`); await page.getByRole('button', { name: '刷新文献' }).click(); await expect(page.locator('.document-row')).toHaveCount(1)
    await page.route(`**/api/documents/${document.id}/content`, route => route.abort()); await page.getByRole('button', { name: '查看原文' }).click()
    await expect(page.getByText('无法连接后端或请求超时，请确认后端已启动，然后重新检查。')).toBeVisible()
    await page.unroute(`**/api/documents/${document.id}/content`); await page.getByRole('button', { name: '重新加载原文' }).click(); await rendered(page, 1)
  })
  test('private PDF deep link retains page after login and binary expiry returns to login', async ({ page }) => {
    await signIn(page); const base = await create(page, 'A6 文献深链'); const document = await upload(page, base)
    await page.getByRole('button', { name: '退出登录' }).click(); await expect(page).toHaveURL(/\/login/)
    await page.goto(`/app/knowledge-bases/${base}/documents/${document.id}?page=2`); await expect(page).toHaveURL(/\/login\?redirect=/)
    await signIn(page); await rendered(page, 2)
    // Invalidate the actual server Session while leaving the page's in-memory
    // profile untouched, then let the real protected binary endpoint return 401.
    const token = await page.request.get('/api/auth/csrf').then(response => response.json())
    expect((await page.request.post('/api/auth/logout', { headers: { 'X-CSRF-TOKEN': token.token } })).status()).toBe(204)
    await page.getByRole('button', { name: '重新加载原文' }).click(); await expect(page).toHaveURL(/\/login\?redirect=/); await expect(page.getByText('登录会话已失效，请重新登录。')).toBeVisible()
  })
  test('document deletion failure is visible and retains a retryable row', async ({ page }) => {
    await signIn(page); const base = await create(page, 'A6 删除故障'); const document = await upload(page, base)
    await page.route(`**/api/documents/${document.id}`, route => route.request().method() === 'DELETE'
      ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'STORAGE_FILE_IN_USE', message: '原件正在被其他程序占用，请关闭后重试' }) }) : route.continue())
    await page.getByRole('button', { name: '删除文献' }).click(); await page.getByRole('button', { name: '确认删除', exact: true }).click()
    await expect(page.getByText('原件正在被其他程序占用，请关闭后重试')).toBeVisible(); await expect(page.locator('.document-row')).toHaveCount(1)
    await page.unroute(`**/api/documents/${document.id}`); await page.getByRole('button', { name: '删除文献' }).click(); await page.getByRole('button', { name: '确认删除', exact: true }).click(); await expect(page.locator('.document-row')).toHaveCount(0)
  })
  test('list, toolbar and canvas fit ordinary laptops and route changes release preview resources', async ({ page }) => {
    await signIn(page); const base = await create(page, 'A6 笔记本布局'); const document = await upload(page, base)
    for (const width of [1366, 1024]) {
      await page.setViewportSize({ width, height: 768 }); await page.goto(`/app/knowledge-bases/${base}/documents/${document.id}?page=3`); await rendered(page, 3)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await page.goto(`/app/knowledge-bases/${base}/documents`); await expect(page.locator('.document-row')).toHaveCount(1); await expect(page.locator('canvas')).toHaveCount(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    }
  })
  test('a completed upload response delayed across a library switch cannot populate the new library', async ({ page }) => {
    await signIn(page); const k1 = await create(page, 'A6 迟到上传 K1'), k2 = await create(page, 'A6 迟到上传 K2')
    let allow!: () => void, saved!: (id: string) => void
    const gate = new Promise<void>(done => { allow = done }), persisted = new Promise<string>(done => { saved = done })
    await page.route(`**/api/knowledge-bases/${k1}/documents`, async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      const response = await route.fetch(); expect(response.status()).toBe(201); saved((await response.json()).id)
      await gate
      if (!route.request().failure()) await route.fulfill({ response })
    })
    await page.goto(`/app/knowledge-bases/${k1}/documents`); await expect(page.getByText('当前知识库还没有文献，请上传 PDF 原件。')).toBeVisible()
    await page.locator('#pdf-file').setInputFiles({ name: filename, mimeType: 'application/pdf', buffer: fixture }); await page.getByRole('button', { name: '开始上传', exact: true }).click()
    const documentId = await persisted
    await page.locator('.current-library-bar .el-select').click(); await page.getByRole('option', { name: 'A6 迟到上传 K2', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/knowledge-bases/${k2}/documents$`)); allow()
    await expect(page.getByText('当前知识库还没有文献，请上传 PDF 原件。')).toBeVisible(); await expect(page.locator('.document-row')).toHaveCount(0)
    expect((await page.request.get(`/api/documents/${documentId}`).then(response => response.json())).knowledgeBaseId).toBe(k1)
    expect((await page.request.get(`/api/knowledge-bases/${k2}`).then(response => response.json())).documentCount).toBe(0)
    await page.unroute(`**/api/knowledge-bases/${k1}/documents`); await page.goto(`/app/knowledge-bases/${k1}/documents`); await expect(page.locator('.document-row')).toHaveCount(1)
  })
})
