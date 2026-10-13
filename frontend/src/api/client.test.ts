import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCsrf, getCsrf, mutate, onUnauthorized, request } from './client'

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
beforeEach(() => { clearCsrf(); onUnauthorized(() => {}) })
afterEach(() => vi.unstubAllGlobals())

describe('same-origin session and CSRF client', () => {
  it('sends cookie credentials and preserves headers', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ ok: true }))
    vi.stubGlobal('fetch', fetch)
    await request('/api/auth/me')
    expect(fetch.mock.calls[0][1].credentials).toBe('same-origin')
    expect(fetch.mock.calls[0][1].cache).toBe('no-store')
  })
  it('shares one CSRF fetch and caches only in memory', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }))
    vi.stubGlobal('fetch', fetch)
    expect(await Promise.all([getCsrf(), getCsrf()])).toEqual(['test-token', 'test-token'])
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('refreshes a rejected CSRF once without retrying other write errors', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'old' }))
      .mockResolvedValueOnce(json({ code: 'CSRF_INVALID', message: 'CSRF 校验失败' }, 403))
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'new' }))
      .mockResolvedValueOnce(json({ ok: true }))
    vi.stubGlobal('fetch', fetch)
    await expect(mutate('/api/auth/login', 'username=test')).resolves.toEqual({ ok: true })
    expect(fetch.mock.calls[3][1].headers.get('X-CSRF-TOKEN')).toBe('new')
    expect(fetch).toHaveBeenCalledTimes(4)
  })
  it('does not retry an uncertain network failure', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      .mockRejectedValueOnce(new TypeError('network'))
    vi.stubGlobal('fetch', fetch)
    await expect(mutate('/api/auth/setup', '{}')).rejects.toMatchObject({ code: 'NETWORK_UNAVAILABLE' })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('distinguishes wrong credentials from an expired private session', async () => {
    const expired = vi.fn()
    onUnauthorized(expired)
    const fetch = vi.fn().mockResolvedValueOnce(json({ code: 'INVALID_CREDENTIALS', message: '账号或密码错误' }, 401))
      .mockResolvedValueOnce(json({ code: 'AUTHENTICATION_REQUIRED', message: '请先登录' }, 401))
    vi.stubGlobal('fetch', fetch)
    await expect(request('/api/auth/login')).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })
    expect(expired).not.toHaveBeenCalled()
    await expect(request('/api/auth/me')).rejects.toMatchObject({ code: 'AUTHENTICATION_REQUIRED' })
    expect(expired).toHaveBeenCalledOnce()
  })
  it('does not notify expiry for an expected anonymous bootstrap', async () => {
    const expired = vi.fn()
    onUnauthorized(expired)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ code: 'AUTHENTICATION_REQUIRED', message: '请先登录' }, 401)))
    await expect(request('/api/auth/me', { notifyUnauthorized: false })).rejects.toMatchObject({ status: 401 })
    expect(expired).not.toHaveBeenCalled()
  })
})
