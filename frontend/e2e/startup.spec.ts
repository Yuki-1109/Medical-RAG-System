import { expect, test } from '@playwright/test'

test('shows actual backend/database status through the Vite proxy', async ({ page, request }) => {
  const health = await request.get('/api/health')
  expect(health.status()).toBe(200)
  const payload = await health.json()
  expect(payload.database.status).toBe('UP')
  expect(payload.pgvector.status).toBe('UP')
  expect(payload.migrations.status).toBe('UP')
  await page.goto('/health')
  await expect(page.getByRole('heading', { name: '医疗 RAG 系统 · 启动检查' })).toBeVisible()
  await expect(page.getByText('工程启动成功：后端、数据库、扩展及迁移均正常。')).toBeVisible()
  await expect(page.getByText('正常', { exact: true })).toHaveCount(4)
  for (const status of await page.getByText('正常', { exact: true }).all()) {
    await expect(status).toBeVisible()
  }
  await page.getByRole('button', { name: '重新检查' }).click()
  await expect(page.getByText('正常', { exact: true })).toHaveCount(4)
})

test('shows connection failure and can retry after recovery', async ({ page }) => {
  await page.route('**/api/health', (route) => route.abort())
  await page.goto('/health')
  await expect(page.getByText('无法连接后端或请求超时，请确认后端已启动，然后重新检查。')).toBeVisible()
  await expect(page.getByText('工程启动成功', { exact: false })).toHaveCount(0)
  await page.unroute('**/api/health')
  await page.getByRole('button', { name: '重新检查' }).click()
  await expect(page.getByText('正常', { exact: true })).toHaveCount(4)
})
