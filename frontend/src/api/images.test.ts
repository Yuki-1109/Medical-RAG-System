import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { clearCsrf, onUnauthorized } from './client'
import { getImage, listImages, imageLimits, originalImage, validateImageFile, validateImageMetadata } from './images'
import { confirmDeleteImage } from '../image/actions'
import { safeDestination } from '../auth/redirect'
import { ElMessageBox } from 'element-plus'
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))
const image = { id: '9007199254740993', knowledgeBaseId: '12', originalFilename: '合成 原件.png', fileFormat: 'PNG' as const, fileSize: 12, sha256: 'a'.repeat(64), width: 32, height: 24,
  imageType: 'UNCLASSIFIED' as const, stage: '', severity: '', keywords: '', note: '', lifecycleStatus: 'ACTIVE' as const, cleanupError: null, createdAt: '2026-10-14T00:00:00Z', updatedAt: '2026-10-14T00:00:00Z' }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
beforeEach(() => { clearCsrf(); onUnauthorized(() => {}); vi.mocked(ElMessageBox.confirm).mockReset() })
afterEach(() => vi.unstubAllGlobals())
it('real metadata preserves BIGINT and validates width, type and library', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json(image)).mockResolvedValueOnce(json([image])).mockResolvedValueOnce(json({ ...image, width: 0 })))
  expect((await getImage(image.id)).id).toBe(image.id)
  await expect(listImages('13')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  await expect(getImage(image.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  await expect(getImage('../1')).rejects.toMatchObject({ code: 'INVALID_IMAGE_ID' })
})
it('configured image limits are real and file selection rejects empty, size, paths and unsupported formats', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ maxImageBytes: 10, maxWidth: 100, maxHeight: 100, maxPixels: 10000 })))
  expect((await imageLimits()).maxImageBytes).toBe(10)
  expect(validateImageFile(new File(['abc'], '合成.JPEG'), 3)).toBe('')
  expect(validateImageFile(new File([], 'empty.png'), 3)).toContain('空')
  expect(validateImageFile(new File(['abcd'], 'large.png'), 3)).toContain('上限')
  for (const name of ['../a.png', 'C:\\a.png', 'test.svg']) expect(validateImageFile(new File(['abc'], name), 3)).not.toBe('')
})
it('binary images accept only JPEG/PNG, use Session and route expired sessions through common client', async () => {
  const expired = vi.fn(); onUnauthorized(expired)
  const fetch = vi.fn().mockResolvedValueOnce(new Response('pixels', { headers: { 'Content-Type': 'image/png' } })).mockResolvedValueOnce(new Response('<svg/>', { headers: { 'Content-Type': 'image/svg+xml' } })).mockResolvedValueOnce(json({ code: 'AUTHENTICATION_REQUIRED', message: '登录已过期' }, 401))
  vi.stubGlobal('fetch', fetch)
  expect((await originalImage('12')).size).toBe(6)
  expect(fetch.mock.calls[0][1].credentials).toBe('same-origin')
  await expect(originalImage('12')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  await expect(originalImage('12')).rejects.toMatchObject({ status: 401 }); expect(expired).toHaveBeenCalledOnce()
})
it('manual metadata is plain text with real length and enum checks', () => {
  expect(validateImageMetadata(image)).toBe('')
  for (const note of ['<script>alert(1)</script>', 'a'.repeat(2001)]) expect(validateImageMetadata({ ...image, note })).not.toBe('')
  expect(validateImageMetadata({ ...image, stage: 'a'.repeat(101) })).not.toBe('')
})
it('cancel sends no request; confirmation sends CSRF delete and errors remain visible', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  expect(await confirmDeleteImage(image)).toBe(false); expect(fetch).not.toHaveBeenCalled()
  vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  fetch.mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'synthetic' })).mockResolvedValueOnce(json({ code: 'STORAGE_FILE_IN_USE', message: '原件占用' }, 503))
  await expect(confirmDeleteImage(image)).rejects.toMatchObject({ code: 'STORAGE_FILE_IN_USE' })
  expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'DELETE', credentials: 'same-origin' })
})
it('image deep links cannot include paths or escape login redirect', () => {
  for (const path of ['/app/knowledge-bases/12/images', `/app/knowledge-bases/12/images/${image.id}`]) expect(safeDestination(path)).toBe(path)
  expect(safeDestination('/app/knowledge-bases/12/images/1?path=../x')).toBe('/app')
})
