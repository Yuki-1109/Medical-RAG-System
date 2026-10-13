import { ApiError, mutate, request, uploadForm } from './client'
import { validKnowledgeBaseId } from './knowledgeBases'

export interface DocumentInfo {
  id: string; knowledgeBaseId: string; originalFilename: string; fileSize: number | null; sha256: string | null
  pageCount: number | null; processingStatus: 'PENDING' | 'PROCESSING' | 'INDEXED' | 'FAILED'
  errorReason: string | null; lifecycleStatus: 'UPLOADING' | 'ACTIVE' | 'DELETING' | 'DELETE_FAILED'
  cleanupError: string | null; createdAt: string; updatedAt: string
}
function id(value: string) {
  if (!validKnowledgeBaseId(value)) throw new ApiError(400, 'INVALID_DOCUMENT_ID', '资源 ID 格式无效，请从当前知识库选择。')
  return value
}
function document(value: DocumentInfo): DocumentInfo {
  if (!value || !validKnowledgeBaseId(value.id) || !validKnowledgeBaseId(value.knowledgeBaseId) || typeof value.originalFilename !== 'string'
    || !['PENDING', 'PROCESSING', 'INDEXED', 'FAILED'].includes(value.processingStatus)
    || !['UPLOADING', 'ACTIVE', 'DELETING', 'DELETE_FAILED'].includes(value.lifecycleStatus)
    || !Number.isFinite(Date.parse(value.createdAt)) || !Number.isFinite(Date.parse(value.updatedAt))
    || (value.fileSize !== null && (!Number.isSafeInteger(value.fileSize) || value.fileSize <= 0))
    || (value.pageCount !== null && (!Number.isSafeInteger(value.pageCount) || value.pageCount < 1 || value.pageCount > 10000))
    || (value.sha256 !== null && !/^[0-9a-f]{64}$/.test(value.sha256))
    || (value.lifecycleStatus === 'ACTIVE' && (!value.fileSize || !value.pageCount || !value.sha256))) {
    throw new ApiError(0, 'INVALID_RESPONSE', '文献响应无效，请检查后端状态。')
  }
  return value
}
export async function listDocuments(base: string): Promise<DocumentInfo[]> {
  const values = await request<DocumentInfo[]>(`/api/knowledge-bases/${id(base)}/documents`)
  if (!Array.isArray(values)) throw new ApiError(0, 'INVALID_RESPONSE', '文献列表响应无效，请重试。')
  const result = values.map(document)
  if (result.some(value => value.knowledgeBaseId !== base)) throw new ApiError(0, 'INVALID_RESPONSE', '文献与当前知识库不一致，请重新加载。')
  return result
}
export async function getDocument(documentId: string) { return document(await request<DocumentInfo>(`/api/documents/${id(documentId)}`)) }
export async function uploadLimits() {
  const value = await request<{ maxPdfBytes: number }>('/api/documents/upload-limits')
  if (!value || !Number.isSafeInteger(value.maxPdfBytes) || value.maxPdfBytes < 1) throw new ApiError(0, 'INVALID_RESPONSE', '文件大小配置无效，请检查后端。')
  return value.maxPdfBytes
}
export async function uploadDocument(base: string, file: File, progress: (value: number) => void, signal: AbortSignal) {
  const body = new FormData(); body.set('file', file)
  const result = document(await uploadForm<DocumentInfo>(`/api/knowledge-bases/${id(base)}/documents`, body, progress, signal))
  if (result.knowledgeBaseId !== base) throw new ApiError(0, 'INVALID_RESPONSE', '上传结果与所选知识库不一致，请刷新检查。')
  return result
}
export function originalDocument(documentId: string, download = false, signal?: AbortSignal) {
  return request<Blob>(`/api/documents/${id(documentId)}/${download ? 'download' : 'content'}`, { responseType: 'blob', signal: signal ?? AbortSignal.timeout(120000) })
}
export async function deleteDocument(documentId: string) { await mutate(`/api/documents/${id(documentId)}`, undefined, 'application/json', true, 'DELETE') }
export function validatePdf(file: File, maxBytes: number) {
  if (!/\.pdf$/i.test(file.name) || /[\\/:\u0000-\u001f\u007f-\u009f]/.test(file.name)) return '请选择名称有效的 PDF 文件，不能包含路径或控制字符。'
  if (!file.size) return '空文件不能上传。'
  if (file.size > maxBytes) return `PDF 超过大小上限（${formatBytes(maxBytes)}）。`
  return ''
}
export function formatBytes(bytes: number | null) { return bytes === null ? '尚未完成' : bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1048576).toFixed(1)} MiB` }
export function processingLabel(value: DocumentInfo['processingStatus']) { return { PENDING: '待处理', PROCESSING: '处理中', INDEXED: '已索引', FAILED: '处理失败' }[value] }
