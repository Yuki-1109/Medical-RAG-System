import { describe, expect, it, vi } from 'vitest'
import { listModels, modelStatus, saveModel, testModel, type ModelInfo } from './models'
import { getCsrf, mutate, request } from './client'
vi.mock('./client', () => ({ getCsrf: vi.fn().mockResolvedValue('synthetic-csrf'), mutate: vi.fn(), request: vi.fn() }))
describe('model API', () => {
  it('uses same-origin GET and CSRF-protected PUT with explicit key handling', async () => {
    await listModels(); expect(request).toHaveBeenCalledWith('/api/settings/models')
    const input = { type: 'CHAT' as const, protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.example.com/v1', modelId: 'test', apiKey: '', deleteKey: false }
    await saveModel(input); expect(mutate).toHaveBeenCalledWith('/api/settings/models', JSON.stringify(input), 'application/json', true, 'PUT')
  })
  it('only tests the selected saved type without sending a draft key', async () => {
    await testModel('EMBEDDING'); expect(getCsrf).toHaveBeenCalled()
    expect(request).toHaveBeenCalledWith('/api/settings/models/test', expect.objectContaining({ method: 'POST', body: '{"type":"EMBEDDING"}', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'synthetic-csrf' } }))
  })
  it('distinguishes unconfigured saved untested success and failure', () => {
    const base = { configured: false, testStatus: 'UNTESTED' } as ModelInfo
    expect(modelStatus(base)).toBe('未配置'); expect(modelStatus({ ...base, configured: true })).toBe('已配置，尚未验证')
    expect(modelStatus({ ...base, configured: true, testStatus: 'SUCCESS' })).toBe('连接测试成功'); expect(modelStatus({ ...base, configured: true, testStatus: 'FAILED' })).toBe('测试失败')
  })
})
