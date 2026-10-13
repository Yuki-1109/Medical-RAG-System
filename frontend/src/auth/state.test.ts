import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { auth, login, resolveAuthentication, setupAdministrator } from './state'
import { clearCsrf } from '../api/client'
import { safeDestination } from './redirect'

const user = { id: 1, username: 'existing-test-admin', createdAt: '2026-10-13T00:00:00Z' }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
beforeEach(() => {
  clearCsrf()
  Object.assign(auth, { user: null, initialized: null, loading: false, connectionError: '', notice: '', suggestedUsername: '' })
})
afterEach(() => vi.unstubAllGlobals())

it('restores an existing administrator from me without initializing or persisting credentials', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(json({ initialized: true })).mockResolvedValueOnce(json(user))
  vi.stubGlobal('fetch', fetch)
  await resolveAuthentication()
  expect(auth.user).toEqual(user)
  expect(fetch.mock.calls.map((call) => call[0])).toEqual(['/api/auth/setup-status', '/api/auth/me'])
})
it('routes a new installation according to the actual false flag, with no me call', async () => {
  const fetch = vi.fn().mockResolvedValue(json({ initialized: false }))
  vi.stubGlobal('fetch', fetch)
  await resolveAuthentication()
  expect(auth.initialized).toBe(false)
  expect(auth.user).toBeNull()
  expect(fetch).toHaveBeenCalledTimes(1)
})
it('successful setup requires a separate login', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test' })).mockResolvedValueOnce(json(user, 201)))
  await setupAdministrator('test-admin', 'test-password-only')
  expect(auth.user).toBeNull()
  expect(auth.initialized).toBe(true)
  expect(auth.notice).toContain('请使用刚设置的账号登录')
})
it('refreshes CSRF after login succeeds', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'before' }))
    .mockResolvedValueOnce(json(user)).mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'after' }))
  vi.stubGlobal('fetch', fetch)
  await login('test-admin', 'test-password-only')
  expect(fetch.mock.calls.map((call) => call[0])).toEqual(['/api/auth/csrf', '/api/auth/login', '/api/auth/csrf'])
  expect(auth.user).toEqual(user)
})
it('reports backend failure rather than pretending installation is empty', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')))
  await expect(resolveAuthentication()).rejects.toMatchObject({ code: 'NETWORK_UNAVAILABLE' })
  expect(auth.initialized).toBeNull()
  expect(auth.connectionError).toContain('无法连接后端')
})
it('allows only known private return paths', () => {
  for (const target of ['https://example.com', '//example.com', '/setup', ['/app']]) expect(safeDestination(target)).toBe('/app')
  expect(safeDestination('/app/health')).toBe('/app/health')
})
