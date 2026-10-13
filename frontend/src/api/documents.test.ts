import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { clearCsrf, onUnauthorized, request } from './client'
import { deleteDocument, formatBytes, getDocument, listDocuments, processingLabel, uploadLimits, validatePdf } from './documents'
import { previewPage } from '../document/pages'
import { safeDestination } from '../auth/redirect'
import { confirmDeleteDocument } from '../document/actions'
import { ElMessageBox } from 'element-plus'
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))
const document = { id: '9007199254740993', knowledgeBaseId: '12', originalFilename: '合成 research.pdf', fileSize: 1234, sha256: 'a'.repeat(64), pageCount: 3,
  processingStatus: 'PENDING' as const, lifecycleStatus: 'ACTIVE' as const, errorReason: null, cleanupError: null, createdAt: '2026-10-14T00:00:00Z', updatedAt: '2026-10-14T00:00:00Z' }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
beforeEach(() => { clearCsrf(); onUnauthorized(() => {}); vi.mocked(ElMessageBox.confirm).mockReset() })
afterEach(() => vi.unstubAllGlobals())
it('preserves BIGINT IDs, real metadata and pending labels', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(document)))
  expect((await getDocument(document.id)).id).toBe(document.id)
  expect(processingLabel(document.processingStatus)).toBe('待处理')
  expect(formatBytes(document.fileSize)).toBe('1.2 KiB')
})
it('rejects cross-library, fake indexed shapes and invalid identifiers before displaying data', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(json([document])).mockResolvedValueOnce(json({ ...document, pageCount: 0 }))
  vi.stubGlobal('fetch', fetch)
  await expect(listDocuments('13')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  await expect(getDocument(document.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  await expect(getDocument('../1')).rejects.toMatchObject({ code: 'INVALID_DOCUMENT_ID' })
  expect(fetch).toHaveBeenCalledTimes(2)
})
it('reads actual configured size and validates name, empty and over-limit inputs', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ maxPdfBytes: 2048 })))
  expect(await uploadLimits()).toBe(2048)
  expect(validatePdf(new File(['abc'], 'valid.PDF'), 3)).toBe('')
  expect(validatePdf(new File(['abcd'], 'valid.pdf'), 3)).toContain('上限')
  expect(validatePdf(new File([], 'empty.pdf'), 100)).toContain('空文件')
  expect(validatePdf(new File(['abc'], '../x.pdf'), 100)).toContain('路径')
  expect(validatePdf(new File(['abc'], 'x.png'), 100)).toContain('PDF')
})
it('protected binary responses share expiry handling and reject HTML fake successes', async () => {
  const expired = vi.fn(); onUnauthorized(expired)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ code: 'AUTHENTICATION_REQUIRED', message: '请先登录' }, 401)).mockResolvedValueOnce(new Response('html', { headers: { 'Content-Type': 'text/html' } })))
  await expect(request('/api/documents/1/content', { responseType: 'blob' })).rejects.toMatchObject({ status: 401 })
  expect(expired).toHaveBeenCalledOnce()
  await expect(request('/api/documents/1/content', { responseType: 'blob' })).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})
it('cancelled document deletion sends no request; only confirmation sends Session/CSRF DELETE', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  expect(await confirmDeleteDocument(document)).toBe(false); expect(fetch).not.toHaveBeenCalled()
  vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  fetch.mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'synthetic' })).mockResolvedValueOnce(new Response(null, { status: 204 }))
  expect(await confirmDeleteDocument(document)).toBe(true)
  expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'DELETE', credentials: 'same-origin' })
  expect(fetch.mock.calls[1][1].headers.get('X-CSRF-TOKEN')).toBe('synthetic')
})
it('deletion errors remain errors and page/deep-link bounds cannot escape to arbitrary routes', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'synthetic' })).mockResolvedValueOnce(json({ code: 'STORAGE_FILE_IN_USE', message: '原件被占用' }, 503)))
  await expect(deleteDocument('12')).rejects.toMatchObject({ code: 'STORAGE_FILE_IN_USE' })
  for (const value of [0, -1, '1.5', '4', 'abc', '', null, ['2']]) expect(previewPage(value, 3)).toBeNull()
  expect(previewPage('3', 3)).toBe(3)
  const link = `/app/knowledge-bases/12/documents/${document.id}?page=2`
  expect(safeDestination(link)).toBe(link)
  expect(safeDestination(link + '&path=../private')).toBe('/app')
})
