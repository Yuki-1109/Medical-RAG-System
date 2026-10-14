import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ModelConfigForm from './ModelConfigForm.vue'
import { listModels, saveModel, testModel, type ModelInfo } from '../api/models'
vi.mock('../api/models', async importOriginal => ({ ...await importOriginal<typeof import('../api/models')>(), listModels: vi.fn(), saveModel: vi.fn(), testModel: vi.fn() }))
const initial: ModelInfo = { type: 'CHAT', protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.example.com/v1', modelId: 'chat-one', keyConfigured: true, configured: true, testStatus: 'UNTESTED', testCode: null, testMessage: null, testedAt: null, embeddingDimension: null, revision: 1 }
async function form(config = initial) { const wrapper = mount(ModelConfigForm, { props: { config } }); await flushPromises(); return wrapper }
beforeEach(() => vi.clearAllMocks())
describe('independent model form', () => {
  it('never fills a saved key and empty means preserve when only model ID changes', async () => {
    const wrapper = await form(); expect(wrapper.get('#CHAT-key').element).toHaveProperty('value', '')
    await wrapper.get('#CHAT-model').setValue('chat-two'); vi.mocked(saveModel).mockResolvedValue({ ...initial, modelId: 'chat-two', revision: 2 })
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(saveModel).toHaveBeenCalledWith(expect.objectContaining({ type: 'CHAT', modelId: 'chat-two', apiKey: '', deleteKey: false })); expect(wrapper.emitted('updated')).toHaveLength(1)
  })
  it('preserves input when save fails and shows failure without success', async () => {
    const wrapper = await form(); await wrapper.get('#CHAT-key').setValue('synthetic-only'); vi.mocked(saveModel).mockRejectedValue(new Error('数据库暂时不可用'))
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('#CHAT-key').element).toHaveProperty('value', 'synthetic-only'); expect(wrapper.text()).toContain('数据库暂时不可用'); expect(wrapper.find('.el-alert--success').exists()).toBe(false); expect(wrapper.emitted('updated')).toBeUndefined()
  })
  it('requires explicit deletion and clears draft key on deletion selection', async () => {
    const wrapper = await form(); await wrapper.get('#CHAT-key').setValue('synthetic-only'); await wrapper.get('input[type="checkbox"]').setValue(true)
    vi.mocked(saveModel).mockResolvedValue({ ...initial, configured: false, keyConfigured: false })
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(saveModel).toHaveBeenCalledWith(expect.objectContaining({ apiKey: '', deleteKey: true })); expect(wrapper.get('#CHAT-key').element).toHaveProperty('value', '')
  })
  it('invalidates visible success for unsaved edits and disables testing', async () => {
    const wrapper = await form({ ...initial, testStatus: 'SUCCESS' }); expect(wrapper.text()).toContain('连接测试成功')
    await wrapper.get('#CHAT-url').setValue('https://other.example.com/v1'); expect(wrapper.text()).not.toContain('连接测试成功'); expect(wrapper.text()).toContain('有未保存修改')
    expect(wrapper.findAll('button').find(button => button.text() === '测试连接')?.attributes('disabled')).toBeDefined()
  })
  it('tests embedding alone and displays dimension from a checked backend result', async () => {
    const config: ModelInfo = { ...initial, type: 'EMBEDDING', modelId: 'embed-one' }; const wrapper = await form(config)
    vi.mocked(testModel).mockResolvedValue({ ...config, testStatus: 'SUCCESS', embeddingDimension: 3 })
    await wrapper.findAll('button').find(button => button.text() === '测试连接')!.trigger('click'); await flushPromises()
    expect(testModel).toHaveBeenCalledWith('EMBEDDING'); expect(saveModel).not.toHaveBeenCalled(); expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ type: 'EMBEDDING', embeddingDimension: 3 }); expect(wrapper.text()).toContain('可能需要重建')
  })
  it('shows sanitized failure and fetches persistent status', async () => {
    const wrapper = await form(); vi.mocked(testModel).mockRejectedValue(new Error('API Key 无效')); vi.mocked(listModels).mockResolvedValue([{ ...initial, testStatus: 'FAILED', testCode: 'MODEL_AUTH_FAILED', testMessage: 'API Key 无效' }])
    await wrapper.findAll('button').find(button => button.text() === '测试连接')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('API Key 无效'); expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ type: 'CHAT', testStatus: 'FAILED' })
  })
})
