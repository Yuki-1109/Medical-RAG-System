import { reactive, watch } from 'vue'
import { auth } from '../auth/state'
import { getKnowledgeBase, type KnowledgeBase } from '../api/knowledgeBases'

export const knowledgeBaseContext = reactive({ current: null as KnowledgeBase | null, loading: false, error: '' })
let generation = 0
let requestedId: string | null = null
export function clearCurrentKnowledgeBase(id?: string) {
  if (id && knowledgeBaseContext.current?.id !== id && requestedId !== id) return
  generation++
  requestedId = null
  knowledgeBaseContext.current = null
  knowledgeBaseContext.loading = false
  knowledgeBaseContext.error = ''
}
watch(() => auth.user?.id, () => clearCurrentKnowledgeBase(), { flush: 'sync' })

export async function loadCurrentKnowledgeBase(id: string, background = false) {
  const requestGeneration = ++generation
  requestedId = id
  const retain = background && knowledgeBaseContext.current?.id === id
  if (!retain) knowledgeBaseContext.current = null
  knowledgeBaseContext.loading = !retain
  knowledgeBaseContext.error = ''
  try {
    const current = await getKnowledgeBase(id)
    if (requestGeneration === generation) knowledgeBaseContext.current = current
  } catch (cause) {
    if (requestGeneration === generation) {
      knowledgeBaseContext.current = null
      knowledgeBaseContext.error = cause instanceof Error ? cause.message : '无法加载知识库，请重试。'
    }
  } finally { if (requestGeneration === generation) knowledgeBaseContext.loading = false }
}
