import { ElMessageBox } from 'element-plus'
import { deleteKnowledgeBase, type KnowledgeBase } from '../api/knowledgeBases'
import { clearCurrentKnowledgeBase } from './context'

export async function confirmDeleteKnowledgeBase(base: KnowledgeBase): Promise<boolean> {
  try {
    await ElMessageBox.confirm(`确认删除知识库“${base.name}”吗？当前库没有文献或图片，删除后无法恢复。`, '删除知识库', {
      confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning',
      closeOnClickModal: false, distinguishCancelAndClose: true,
    })
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return false
    throw cause
  }
  await deleteKnowledgeBase(base.id)
  clearCurrentKnowledgeBase(base.id)
  return true
}
