import { expect, test, type Page } from '@playwright/test'
import { createServer, type Server } from 'node:http'
import { once } from 'node:events'
if (process.env.MEDICAL_RAG_E2E_ISOLATED !== '1') throw new Error('B1只允许隔离数据库与本机接口测试替身。')
const username = 'mianmian', password = 'A3-Isolated-Browser-Password-2026'
let stub: Server, url = '', requests = 0, rejectChat = false
async function token(page: Page) { return page.request.get('/api/auth/csrf').then(r => r.json()).then(r => r.token as string) }
async function login(page: Page) {
  if (new URL(page.url()).pathname !== '/login') await page.goto('/login'); await page.getByLabel('用户名', { exact: true }).fill(username); await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page).toHaveURL(location => location.pathname.startsWith('/app'))
}
test.describe.serial('B1 local HTTP substitute, not a third-party supplier', () => {
  test.beforeAll(async ({ request }) => {
    const state = await request.get('/api/auth/setup-status').then(r => r.json())
    if (!state.initialized) {
      const csrf = await request.get('/api/auth/csrf').then(r => r.json())
      expect((await request.post('/api/auth/setup', { headers: { 'X-CSRF-TOKEN': csrf.token }, data: { username, password } })).status()).toBe(201)
    }
    stub = createServer(async (request, response) => {
      requests++; const chunks: Buffer[] = []; for await (const chunk of request) chunks.push(Buffer.from(chunk)); const body = JSON.parse(Buffer.concat(chunks).toString())
      const embedding = request.url === '/v1/embeddings'
      expect(request.headers.cookie).toBeUndefined(); expect(request.headers.authorization).toBe(embedding ? 'Bearer b1-synthetic-embedding' : 'Bearer b1-synthetic-chat')
      response.setHeader('Content-Type', 'application/json')
      if (embedding) {
        expect(body.model).toBe('embed-one'); expect(body.input).toBe('Connection test.'); expect(body.encoding_format).toBe('float')
        response.end(JSON.stringify({ data: [{ index: 0, embedding: [0.1, 0.2, -0.3] }] }))
      } else {
        expect(request.url).toBe('/v1/chat/completions'); expect(body.messages).toEqual([{ role: 'user', content: 'Connection test. Reply briefly.' }]); expect(body.max_tokens).toBe(16)
        if (rejectChat) { response.statusCode = 401; response.end(JSON.stringify({ error: { message: 'private provider detail b1-synthetic-chat' } })); return }
        response.end(JSON.stringify({ choices: [{ message: { content: 'Local substitute response' } }] }))
      }
    }); stub.listen(0, '127.0.0.1'); await once(stub, 'listening')
    const address = stub.address(); if (!address || typeof address === 'string') throw new Error('Missing substitute port'); url = `http://127.0.0.1:${address.port}/v1`
  })
  test.afterAll(async () => { if (stub) await new Promise<void>(resolve => stub.close(() => resolve())) })
  test('settings deep link and direct API require login', async ({ page, request }) => {
    await page.goto('/app/settings/models'); await expect(page).toHaveURL(/\/login\?redirect=/)
    expect((await request.get('/api/settings/models')).status()).toBe(401)
    await login(page); await expect(page).toHaveURL(/\/app\/settings\/models$/); await expect(page.locator('.model-config-card')).toHaveCount(2)
  })
  test('independent real calls, empty key preservation, failures, reload and backend restart', async ({ page }) => {
    test.setTimeout(90000); await login(page); await page.getByRole('link', { name: '系统设置', exact: true }).click()
    const chat = page.locator('[data-model-type="CHAT"]'), embedding = page.locator('[data-model-type="EMBEDDING"]')
    for (const [card, model, key] of [[chat, 'chat-one', 'b1-synthetic-chat'], [embedding, 'embed-one', 'b1-synthetic-embedding']] as const) {
      await card.getByLabel('API Base URL', { exact: true }).fill(url); await card.getByLabel('Model ID', { exact: true }).fill(model); await card.getByLabel('API Key', { exact: true }).fill(key)
      await card.getByRole('button', { name: '保存配置', exact: true }).click(); await expect(card.getByText('已配置，尚未验证', { exact: true })).toBeVisible(); await expect(card.getByLabel('API Key', { exact: true })).toHaveValue('')
    }
    expect(requests).toBe(0)
    for (const card of [chat, embedding]) { await card.getByRole('button', { name: '测试连接', exact: true }).click(); await expect(card.getByText('连接测试成功', { exact: true })).toBeVisible() }
    expect(requests).toBe(2); await expect(embedding.getByText(/向量维度 3/)).toBeVisible()
    expect((await page.request.put('/api/settings/models', { data: { type: 'CHAT' } })).status()).toBe(403)
    expect((await page.request.post('/api/settings/models/test', { headers: { 'X-CSRF-TOKEN': 'wrong' }, data: { type: 'CHAT' } })).status()).toBe(403)
    const payload = await page.request.get('/api/settings/models').then(r => r.text()); expect(payload).not.toMatch(/b1-synthetic|encrypted_api_key|apiKey|cipher/)
    await chat.getByLabel('Model ID', { exact: true }).fill('chat-two'); await expect(chat.getByText('连接测试成功', { exact: true })).toHaveCount(0)
    await chat.getByRole('button', { name: '保存配置', exact: true }).click(); await expect(chat.getByText('已配置，尚未验证', { exact: true })).toBeVisible(); await expect(embedding.getByText('连接测试成功', { exact: true })).toBeVisible()
    await chat.getByRole('button', { name: '测试连接', exact: true }).click(); await expect(chat.getByText('连接测试成功', { exact: true })).toBeVisible()
    rejectChat = true; await chat.getByRole('button', { name: '测试连接', exact: true }).click(); await expect(chat.getByText('测试失败', { exact: true })).toBeVisible(); await expect(chat.getByRole('alert').filter({ hasText: 'API Key 无效' })).toBeVisible(); expect(await page.locator('main').innerText()).not.toContain('private provider detail')
    await expect(embedding.getByText('连接测试成功', { exact: true })).toBeVisible(); await page.reload(); await expect(chat.getByText('测试失败', { exact: true })).toBeVisible(); await expect(chat.getByLabel('API Key', { exact: true })).toHaveValue('')
    for (const width of [1366, 1024]) { await page.setViewportSize({ width, height: 768 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true) }
    expect((await page.request.post(`${process.env.MEDICAL_RAG_E2E_CONTROL}/restart`, { headers: { Authorization: `Bearer ${process.env.MEDICAL_RAG_E2E_CONTROL_TOKEN}` } })).status()).toBe(204)
    rejectChat = false; await page.reload(); await expect(page).toHaveURL(/\/login/); await login(page); await page.goto('/app/settings/models')
    await expect(chat.getByLabel('Model ID', { exact: true })).toHaveValue('chat-two'); await expect(chat.getByLabel('API Key', { exact: true })).toHaveValue(''); await expect(embedding.getByText('连接测试成功', { exact: true })).toBeVisible()
    await chat.getByRole('button', { name: '测试连接', exact: true }).click(); await expect(chat.getByText('连接测试成功', { exact: true })).toBeVisible()
    await chat.getByRole('checkbox', { name: /删除当前类型/ }).check(); await chat.getByRole('button', { name: '保存配置', exact: true }).click(); await expect(chat.getByText('未配置', { exact: true })).toBeVisible(); await expect(embedding.getByText('连接测试成功', { exact: true })).toBeVisible()
    await page.getByRole('link', { name: '我的知识库', exact: true }).click(); await expect(page.getByRole('heading', { name: '我的知识库', exact: true }).first()).toBeVisible()
  })
  test('matching entry buttons navigate real routes and fit laptop and narrow layouts', async ({ page }) => {
    await login(page); const response = await page.request.post('/api/knowledge-bases', { headers: { 'X-CSRF-TOKEN': await token(page) }, data: { name: 'B1 入口验收', description: 'synthetic' } }); expect(response.status()).toBe(201); const base = await response.json()
    try {
      await page.goto(`/app/knowledge-bases/${base.id}`); const buttons = page.locator('.library-entry-actions button'); await expect(buttons).toHaveCount(2)
      const styles = await buttons.evaluateAll(items => items.map(item => { const s = getComputedStyle(item); return [s.fontFamily, s.fontSize, s.border, s.backgroundColor, s.height, s.borderRadius] })); expect(styles[0]).toEqual(styles[1])
      for (const width of [1366, 1024, 620]) { await page.setViewportSize({ width, height: 768 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true) }
      await page.getByRole('button', { name: '进入文献管理', exact: true }).click(); await expect(page).toHaveURL(new RegExp(`/knowledge-bases/${base.id}/documents$`)); await expect(page.locator('#pdf-file')).toBeVisible()
      await page.goto(`/app/knowledge-bases/${base.id}`); await page.getByRole('button', { name: '进入医学图片', exact: true }).click(); await expect(page).toHaveURL(new RegExp(`/knowledge-bases/${base.id}/images$`)); await expect(page.locator('#image-file')).toBeVisible()
    } finally { expect((await page.request.delete(`/api/knowledge-bases/${base.id}`, { headers: { 'X-CSRF-TOKEN': await token(page) } })).status()).toBe(204) }
  })
})
