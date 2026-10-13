import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { clearCsrf } from './client'
import { createKnowledgeBase, deleteKnowledgeBase, getKnowledgeBase, listKnowledgeBases, updateKnowledgeBase, validKnowledgeBaseId, validateKnowledgeBase } from './knowledgeBases'

const base = { id: '9007199254740993', name: '真实库', description: '', demo: false, createdAt: '2026-10-13T00:00:00Z', updatedAt: '2026-10-13T00:00:00Z', documentCount: 0, imageCount: 0 }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
beforeEach(clearCsrf)
afterEach(() => vi.unstubAllGlobals())
it('keeps BIGINT IDs exact and validates private paths', async () => {
  const fetch = vi.fn().mockResolvedValue(json(base))
  vi.stubGlobal('fetch', fetch)
  expect((await getKnowledgeBase(base.id)).id).toBe('9007199254740993')
  expect(fetch.mock.calls[0][0]).toBe('/api/knowledge-bases/9007199254740993')
  for (const id of ['01', '../1', '0', '9223372036854775808', 1]) expect(validKnowledgeBaseId(id)).toBe(false)
  await expect(getKnowledgeBase('../1')).rejects.toMatchObject({ code: 'INVALID_KNOWLEDGE_BASE_ID' })
  expect(fetch).toHaveBeenCalledTimes(1)
})
it('uses actual counts, refuses invalid response shapes and copies only public fields', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json([{ ...base, documentCount: 5, imageCount: 2, privateField: 'excluded' }]))
    .mockResolvedValueOnce(json({})).mockResolvedValueOnce(json([{ ...base, id: 1 }])))
  const values = await listKnowledgeBases()
  expect(values[0]).toMatchObject({ documentCount: 5, imageCount: 2 })
  expect(values[0]).not.toHaveProperty('privateField')
  await expect(listKnowledgeBases()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  await expect(listKnowledgeBases()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})
it('create PATCH and DELETE share Session/CSRF without sending ownership', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-only' }))
    .mockResolvedValueOnce(json(base, 201)).mockResolvedValueOnce(json(base)).mockResolvedValueOnce(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetch)
  await createKnowledgeBase({ name: '库', description: '' })
  await updateKnowledgeBase(base.id, { name: '修改', description: '简介' })
  await deleteKnowledgeBase(base.id)
  expect(fetch.mock.calls.slice(1).map(call => call[1].method)).toEqual(['POST', 'PATCH', 'DELETE'])
  for (const call of fetch.mock.calls.slice(1)) {
    expect(call[1].credentials).toBe('same-origin')
    expect(call[1].headers.get('X-CSRF-TOKEN')).toBe('test-only')
    expect(call[1].body || '').not.toContain('owner')
  }
})
it('does not accept unnamed overlong or control-character input', () => {
  for (const name of ['', '   ', '\u3000', 'x'.repeat(101), 'A\u0000B']) expect(validateKnowledgeBase({ name, description: '' })).toContain('名称')
  expect(validateKnowledgeBase({ name: '库', description: 'x'.repeat(2001) })).toContain('简介')
  expect(validateKnowledgeBase({ name: '库', description: '第一段\n第二段' })).toBe('')
})
