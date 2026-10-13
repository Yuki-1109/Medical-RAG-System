import { ElMessageBox } from 'element-plus'
import { deleteDocument, originalDocument, type DocumentInfo } from '../api/documents'

export async function confirmDeleteDocument(document: DocumentInfo) {
  try {
    await ElMessageBox.confirm(`确认删除“${document.originalFilename}”吗？原件将清理，无法恢复；失败时保留记录供重试。`, '删除文献', {
      confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning', closeOnClickModal: false, distinguishCancelAndClose: true,
    })
  } catch (cause) { if (cause === 'cancel' || cause === 'close') return false; throw cause }
  await deleteDocument(document.id)
  return true
}
export async function downloadOriginal(document: DocumentInfo) {
  const original = await originalDocument(document.id, true)
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', await original.arrayBuffer()))
  const hash = Array.from(digest, byte => byte.toString(16).padStart(2, '0')).join('')
  if (original.size !== document.fileSize || hash !== document.sha256) throw new Error('下载原件的大小或 SHA-256 不一致，已阻止保存，请检查存储。')
  const url = URL.createObjectURL(original)
  try {
    const link = window.document.createElement('a'); link.href = url; link.download = document.originalFilename
    window.document.body.append(link); link.click(); link.remove()
  } finally { setTimeout(() => URL.revokeObjectURL(url), 1000) }
}
