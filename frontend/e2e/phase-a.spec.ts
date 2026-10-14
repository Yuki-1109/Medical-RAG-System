import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { syntheticPdf } from './synthetic-pdf'
if (process.env.MEDICAL_RAG_E2E_ISOLATED !== '1') throw new Error('A8 仅操作本次自建隔离环境。')
const username = 'mianmian', password = 'A3-Isolated-Browser-Password-2026'
const digest = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex')
async function token(page: Page) { return (await page.request.get('/api/auth/csrf').then(response => response.json())).token as string }
async function login(page: Page) {
  await page.goto('/login'); await page.getByLabel('用户名', { exact: true }).fill(username); await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page).toHaveURL(/\/app/)
}
test.use({ actionTimeout: 10000 })
test('A8 fresh installation and complete PDF/JPG/PNG lifecycle across actual database and application restart', async ({ page, request }) => {
  test.setTimeout(180000)
  const owned = new Set<string>()
  try {
    await page.goto('/')
    const initialized = (await request.get('/api/auth/setup-status').then(response => response.json())).initialized
    if (!initialized) {
      await expect(page).toHaveURL(/\/setup$/)
      await page.getByLabel('管理员用户名', { exact: true }).fill(username); await page.getByLabel('管理员密码', { exact: true }).fill(password); await page.getByLabel('确认密码').fill(password)
      await page.getByRole('button', { name: '创建管理员', exact: true }).click(); await expect(page).toHaveURL(/\/login(?:\?|$)/)
    }
    await login(page)
    console.log('A8：首次部署/原测试账号登录完成')
    const health = await page.request.get('/api/health').then(response => response.json()); expect(health.migrations.version).toBe('6')
    const profile = await page.request.get('/api/auth/me').then(response => response.json()); expect(Object.keys(profile).sort()).toEqual(['createdAt', 'id', 'username'])
    const k1Response = await page.request.post('/api/knowledge-bases', { headers: { 'X-CSRF-TOKEN': await token(page) }, data: { name: 'A8 综合 K1', description: 'initial' } }); expect(k1Response.status()).toBe(201)
    const k1 = (await k1Response.json()).id as string; owned.add(k1)
    const k2Response = await page.request.post('/api/knowledge-bases', { headers: { 'X-CSRF-TOKEN': await token(page) }, data: { name: 'A8 综合 K2', description: 'preserve independent library' } }); expect(k2Response.status()).toBe(201)
    const k2 = (await k2Response.json()).id as string; owned.add(k2); expect(k1).not.toBe(k2)
    const pdfBytes = syntheticPdf(), jpgBytes = await readFile(join(process.env.MEDICAL_RAG_E2E_FIXTURES!, 'synthetic.jpg')), pngBytes = await readFile(join(process.env.MEDICAL_RAG_E2E_FIXTURES!, 'synthetic.png'))
    const files: { base: string; kind: 'documents' | 'images'; id: string; bytes: Buffer; metadata: Record<string, unknown> }[] = []
    for (const [base, kind, name, bytes, mime] of [
      [k1, 'documents', 'A8 English.pdf', pdfBytes, 'application/pdf'], [k1, 'images', 'A8 teaching.jpg', jpgBytes, 'image/jpeg'], [k1, 'images', 'A8 teaching.png', pngBytes, 'image/png'],
      [k2, 'documents', 'A8 independent.pdf', syntheticPdf(2), 'application/pdf'], [k2, 'images', 'A8 independent.png', pngBytes, 'image/png'],
    ] as const) {
      await page.goto(`/app/knowledge-bases/${base}/${kind}`)
      const input = page.locator(kind === 'images' ? '#image-file' : '#pdf-file'); await expect(input).toBeEnabled(); await input.setInputFiles({ name, mimeType: mime, buffer: bytes })
      const responsePending = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith(`/api/knowledge-bases/${base}/${kind}`))
      await page.getByRole('button', { name: '开始上传', exact: true }).click(); const response = await responsePending; expect(response.status()).toBe(201)
      const metadata = await response.json(); expect(metadata.sha256).toBe(digest(bytes)); expect(metadata.fileSize).toBe(bytes.length)
      console.log(`A8：真实上传 ${kind} (${files.length + 1}/5)`)
      files.push({ base, kind, id: metadata.id, bytes, metadata })
    }
    const pdf = files[0]!, jpg = files[1]!, png = files[2]!
    await page.goto(`/app/knowledge-bases/${k1}/documents/${pdf.id}?page=2`); await expect(page.locator('canvas[data-rendered-page="2"]')).toBeVisible({ timeout: 20000 })
    const colored = await page.locator('canvas[data-rendered-page="2"]').evaluate((canvas: HTMLCanvasElement) => { const data = canvas.getContext('2d')!.getImageData(0, 0, canvas.width, canvas.height).data; let pixels = 0; for (let i = 0; i < data.length; i += 4) if (data[i]! < 240) pixels++; return pixels }); expect(colored).toBeGreaterThan(1000)
    await page.goto(`/app/knowledge-bases/${k1}/images/${jpg.id}`)
    await expect.poll(() => page.locator('.image-detail-grid img').evaluateAll(images => images.map(image => (image as HTMLImageElement).naturalWidth))).toEqual([32])
    await page.getByLabel('分期', { exact: true }).fill('Stage II'); await page.getByLabel('严重程度', { exact: true }).fill('manual mild'); await page.getByLabel('关键词', { exact: true }).fill('anonymous synthetic'); await page.getByLabel('备注', { exact: true }).fill('A8 医生人工备注')
    await page.getByRole('button', { name: '保存人工信息' }).click(); await expect(page.getByText('人工信息已保存，原图未改写。')).toBeVisible()
    expect((await page.request.patch(`/api/knowledge-bases/${k1}`, { headers: { 'X-CSRF-TOKEN': await token(page) }, data: { name: 'A8 综合 K1', description: 'A8 updated persistent description' } })).status()).toBe(200)
    for (const file of files) file.metadata = await page.request.get(`/api/${file.kind}/${file.id}`).then(response => response.json())
    await page.getByRole('button', { name: '退出登录' }).click(); await expect(page).toHaveURL(/\/login/)
    console.log('A8：原件预览、人工编辑及退出完成，准备真实重启')
    const restart = await request.post(`${process.env.MEDICAL_RAG_E2E_CONTROL}/restart-all`, { headers: { Authorization: `Bearer ${process.env.MEDICAL_RAG_E2E_CONTROL_TOKEN}` }, timeout: 60000 }); expect(restart.status()).toBe(204)
    await page.goto('/'); await expect(page).toHaveURL(/\/login(?:\?|$)/); await login(page)
    expect((await page.request.get('/api/auth/me').then(response => response.json())).id).toBe(profile.id)
    expect((await page.request.get(`/api/knowledge-bases/${k1}`).then(response => response.json())).description).toBe('A8 updated persistent description')
    for (const file of files) {
      const current = await page.request.get(`/api/${file.kind}/${file.id}`).then(response => response.json()); expect(current).toEqual(file.metadata)
      const response = await page.request.get(`/api/${file.kind}/${file.id}/download`); expect(response.status()).toBe(200); const downloaded = await response.body(); expect(downloaded.equals(file.bytes)).toBe(true); expect(digest(downloaded)).toBe(file.metadata.sha256)
    }
    for (const base of [k1, k2]) for (const kind of ['documents', 'images']) { const list = await page.request.get(`/api/knowledge-bases/${base}/${kind}`).then(response => response.json()); expect(list.map((file: { id: string }) => file.id).sort()).toEqual(files.filter(file => file.base === base && file.kind === kind).map(file => file.id).sort()) }
    // The request fixture has no page Session. Read/write rejection uses the real backend.
    for (const path of [`/api/knowledge-bases/${k1}/images`, `/api/knowledge-bases/${k1}/documents`, `/api/images/${jpg.id}/content`, `/api/documents/${pdf.id}/download`]) expect((await request.get(path)).status()).toBe(401)
    expect((await page.request.delete(`/api/images/${jpg.id}`)).status()).toBe(403)
    expect((await page.request.patch(`/api/images/${jpg.id}`, { headers: { 'X-CSRF-TOKEN': 'invalid' }, data: { imageType: 'NORMAL' } })).status()).toBe(403)
    expect((await page.request.patch(`/api/images/${jpg.id}`, { headers: { 'X-CSRF-TOKEN': await token(page) }, data: { imageType: 'NORMAL', owner_id: '999' } })).status()).toBe(400)
    // Cancel PDF and image deletions: no DELETE requests. Then delete one image only.
    let deletes = 0; page.on('request', r => { if (r.method() === 'DELETE') deletes++ })
    await page.goto(`/app/knowledge-bases/${k1}/documents`); await page.getByRole('button', { name: '删除文献', exact: true }).click(); await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.goto(`/app/knowledge-bases/${k1}/images/${png.id}`); await expect(page.getByRole('button', { name: '删除图片', exact: true })).toBeVisible(); await page.getByRole('button', { name: '删除图片', exact: true }).click(); await page.getByRole('button', { name: '取消', exact: true }).click(); expect(deletes).toBe(0)
    await page.getByRole('button', { name: '删除图片', exact: true }).click(); await page.getByRole('button', { name: '确认删除', exact: true }).click(); await expect(page).toHaveURL(new RegExp(`/knowledge-bases/${k1}/images$`)); expect((await page.request.get(`/api/images/${png.id}/content`)).status()).toBe(404)
    expect((await page.request.get(`/api/documents/${pdf.id}/content`)).status()).toBe(200); expect((await page.request.get(`/api/images/${jpg.id}/content`)).status()).toBe(200)
    await page.goto('/app/knowledge-bases'); const card = page.locator(`[data-library-id="${k1}"]`); await expect(card).toContainText('文献 1 · 图片 1'); await card.getByRole('button', { name: '删除', exact: true }).click()
    await expect(page.getByText(/包含 1 篇有效文献.*1 张图片/)).toBeVisible(); await page.getByRole('button', { name: '取消', exact: true }).click(); expect((await page.request.get(`/api/images/${jpg.id}/content`)).status()).toBe(200)
    await card.getByRole('button', { name: '删除', exact: true }).click(); await page.getByRole('button', { name: '确认删除', exact: true }).click(); await expect(card).toHaveCount(0); owned.delete(k1)
    for (const file of files.filter(file => file.base === k1)) { expect((await page.request.get(`/api/${file.kind}/${file.id}/content`)).status()).toBe(404); expect((await page.request.get(`/api/${file.kind}/${file.id}`)).status()).toBe(404) }
    for (const file of files.filter(file => file.base === k2)) { const response = await page.request.get(`/api/${file.kind}/${file.id}/download`); expect(response.status()).toBe(200); expect(digest(await response.body())).toBe(digest(file.bytes)) }
  } finally {
    if ((await page.request.get('/api/auth/me')).status() !== 200) await login(page)
    const csrf = await token(page); for (const base of owned) expect([204, 404]).toContain((await page.request.delete(`/api/knowledge-bases/${base}`, { headers: { 'X-CSRF-TOKEN': csrf } })).status())
  }
})
