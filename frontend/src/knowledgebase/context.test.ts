import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { auth, expireAuthentication } from '../auth/state'
import { clearCsrf } from '../api/client'
import { clearCurrentKnowledgeBase, knowledgeBaseContext, loadCurrentKnowledgeBase } from './context'
import { confirmDeleteKnowledgeBase } from './actions'
import { safeDestination } from '../auth/redirect'
import { ElMessageBox } from 'element-plus'
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))
const base = { id: '12', name: 'K1', description: '', demo: false, createdAt: '2026-10-13T00:00:00Z', updatedAt: '2026-10-13T00:00:00Z', documentCount: 0, imageCount: 0 }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
beforeEach(() => { clearCsrf(); clearCurrentKnowledgeBase(); vi.mocked(ElMessageBox.confirm).mockReset() })
afterEach(() => vi.unstubAllGlobals())
it('loads a direct link from the backend, never from stored credentials or cached IDs', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(base)))
  await loadCurrentKnowledgeBase('12')
  expect(knowledgeBaseContext.current?.id).toBe('12')
})
it('missing or foreign resources clear the current context with a readable error', async () => {
  knowledgeBaseContext.current = base
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ code: 'KNOWLEDGE_BASE_NOT_FOUND', message: '知识库不存在或无权访问' }, 404)))
  await loadCurrentKnowledgeBase('99')
  expect(knowledgeBaseContext.current).toBeNull()
  expect(knowledgeBaseContext.error).toContain('不存在或无权访问')
})
it('late responses cannot switch context back to an earlier library', async () => {
  let first!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => new Promise<Response>(done => { first = done }))
    .mockResolvedValueOnce(json({ ...base, id: '13', name: 'K2' })))
  const pending = loadCurrentKnowledgeBase('12')
  await loadCurrentKnowledgeBase('13')
  first(json(base)); await pending
  expect(knowledgeBaseContext.current?.id).toBe('13')
})
it('expiry clears selected data and invalidates an in-flight response', async () => {
  auth.user = { id: 1, username: 'test', createdAt: base.createdAt }
  let done!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise<Response>(resolve => { done = resolve })))
  const pending = loadCurrentKnowledgeBase('12')
  expireAuthentication()
  done(json(base)); await pending
  expect(knowledgeBaseContext.current).toBeNull()
})
it('clearing a deleted library invalidates even its pending detail response', async () => {
  let done!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise<Response>(resolve => { done = resolve })))
  const pending = loadCurrentKnowledgeBase('12')
  clearCurrentKnowledgeBase('12')
  done(json(base)); await pending
  expect(knowledgeBaseContext.current).toBeNull()
  expect(knowledgeBaseContext.loading).toBe(false)
})
it('focus revalidation retains an open editor until a missing-library response clears it', async () => {
  knowledgeBaseContext.current = base
  let done!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise<Response>(resolve => { done = resolve })))
  const pending = loadCurrentKnowledgeBase('12', true)
  expect(knowledgeBaseContext.current?.id).toBe('12')
  expect(knowledgeBaseContext.loading).toBe(false)
  done(json({ code: 'KNOWLEDGE_BASE_NOT_FOUND', message: '知识库不存在或无权访问' }, 404)); await pending
  expect(knowledgeBaseContext.current).toBeNull()
})
it('cancel or close sends no delete and preserves selected data', async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(json(base)))
  vi.stubGlobal('fetch', fetch)
  knowledgeBaseContext.current = base
  for (const action of ['cancel', 'close']) {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce(action)
    expect(await confirmDeleteKnowledgeBase(base)).toBe(false)
  }
  expect(fetch.mock.calls.every(call => call[0] === '/api/knowledge-bases/12' && call[1].method === undefined)).toBe(true)
  expect(knowledgeBaseContext.current?.id).toBe('12')
})
it('only confirmed successful deletion clears the selected library', async () => {
  vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  knowledgeBaseContext.current = base
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json(base)).mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-only' })).mockResolvedValueOnce(new Response(null, { status: 204 })))
  expect(await confirmDeleteKnowledgeBase(base)).toBe(true)
  expect(knowledgeBaseContext.current).toBeNull()
})
it('failed deletion preserves context and reports the backend error', async () => {
  vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  knowledgeBaseContext.current = base
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json(base)).mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-only' })).mockResolvedValueOnce(json({ code: 'DATABASE_UNAVAILABLE', message: '数据库暂时不可用' }, 503)))
  await expect(confirmDeleteKnowledgeBase(base)).rejects.toMatchObject({ status: 503 })
  expect(knowledgeBaseContext.current?.id).toBe('12')
})
it('return URLs allow real library routes and reject queries, paths and external URLs', () => {
  expect(safeDestination('/app/knowledge-bases')).toBe('/app/knowledge-bases')
  expect(safeDestination('/app/knowledge-bases/12')).toBe('/app/knowledge-bases/12')
  for (const value of ['/app/knowledge-bases/../1', '/app/knowledge-bases/12?owner=2', '//example.com', '/app/knowledge-bases/0']) expect(safeDestination(value)).toBe('/app')
})
