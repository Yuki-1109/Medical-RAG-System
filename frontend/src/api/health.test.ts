import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from './health'

afterEach(() => vi.unstubAllGlobals())

describe('health API failure handling', () => {
  it('preserves a real 503 component response for the page', async () => {
    const component = { status: 'UNKNOWN', message: '数据库不可用', version: null }
    const payload = {
      status: 'DOWN', checkedAt: new Date().toISOString(),
      backend: { status: 'UP', message: '已响应', version: null },
      database: { ...component, status: 'DOWN' }, pgvector: component, migrations: component,
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(payload), { status: 503 })))
    expect((await fetchHealth()).database.status).toBe('DOWN')
  })

  it('rejects proxy HTML instead of reporting startup success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>proxy error</html>', { status: 503 })))
    await expect(fetchHealth()).rejects.toThrow('未收到有效')
  })

  it('provides an actionable error when the backend cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(fetchHealth()).rejects.toThrow('确认后端已启动')
  })
})
