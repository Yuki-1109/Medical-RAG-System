import { ElMessageBox } from 'element-plus'
import { deleteKnowledgeBase, getKnowledgeBase, type KnowledgeBase } from '../api/knowledgeBases'
import { clearCurrentKnowledgeBase } from './context'

export async function confirmDeleteKnowledgeBase(base: KnowledgeBase): Promise<boolean> {
  // Refresh the true impact before asking. This GET never deletes anything.
  const latest = await getKnowledgeBase(base.id)
  const impact = latest.documentCount || latest.imageCount || latest.pendingCleanupCount
    ? `包含 ${latest.documentCount} 篇有效文献、${latest.pendingCleanupCount ?? 0} 条待清理记录和 ${latest.imageCount} 张图片；确认后将使资料不可访问并清理原件，无法恢复。`
    : '当前库没有文献或图片，删除后无法恢复。'
  try {
    await ElMessageBox.confirm(`确认删除知识库“${latest.name}”吗？${impact}`, '删除知识库', {
      confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning',
      closeOnClickModal: false, distinguishCancelAndClose: true,
    })
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return false
    throw cause
  }
  await deleteKnowledgeBase(base.id, latest)
  clearCurrentKnowledgeBase(base.id)
  return true
}
