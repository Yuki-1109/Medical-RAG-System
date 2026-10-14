import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import KnowledgeBaseView from './KnowledgeBaseView.vue'
const mocks = vi.hoisted(() => ({ push: vi.fn(), load: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: '1' } }), useRouter: () => ({ push: mocks.push }) }))
vi.mock('../knowledgebase/context', async () => { const { reactive } = await import('vue'); return ({ knowledgeBaseContext: reactive({ loading: false, error: '', current: { id: '1', name: '合成库', description: '', deletionPending: false, documentCount: 1, imageCount: 1, pendingCleanupCount: 0, createdAt: '2026-10-14T00:00:00Z', updatedAt: '2026-10-14T00:00:00Z' } }), loadCurrentKnowledgeBase: mocks.load, clearCurrentKnowledgeBase: vi.fn() }) })
import { knowledgeBaseContext } from '../knowledgebase/context'
beforeEach(() => { mocks.push.mockClear(); knowledgeBaseContext.current!.deletionPending = false })
it('renders matching Element Plus buttons and navigates both original routes', async () => {
  const wrapper = mount(KnowledgeBaseView, { global: { stubs: { RouterLink: true, KnowledgeBaseForm: true } } }); await flushPromises()
  const buttons = wrapper.findAll('.library-entry-actions button'); expect(buttons).toHaveLength(2)
  expect(buttons[0]!.classes()).toEqual(buttons[1]!.classes()); expect(buttons.map(button => button.text())).toEqual(['进入文献管理', '进入医学图片'])
  await buttons[0]!.trigger('click'); expect(mocks.push).toHaveBeenLastCalledWith('/app/knowledge-bases/1/documents')
  await buttons[1]!.trigger('click'); expect(mocks.push).toHaveBeenLastCalledWith('/app/knowledge-bases/1/images')
})
it('disables both entry buttons for pending deletion', async () => {
  knowledgeBaseContext.current!.deletionPending = true
  const wrapper = mount(KnowledgeBaseView, { global: { stubs: { RouterLink: true, KnowledgeBaseForm: true } } }); await flushPromises()
  for (const button of wrapper.findAll('.library-entry-actions button')) expect(button.attributes('disabled')).toBeDefined()
})
