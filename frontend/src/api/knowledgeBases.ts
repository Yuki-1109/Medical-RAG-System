import { ApiError, mutate, request } from './client'

export interface KnowledgeBase {
  id: string; name: string; description: string; demo: boolean
  createdAt: string; updatedAt: string; documentCount: number; imageCount: number
  deletionPending?: boolean; pendingCleanupCount?: number
}
export interface KnowledgeBaseInput { name: string; description: string }

export function validKnowledgeBaseId(id: unknown): id is string {
  return typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n
}
function base(value: KnowledgeBase): KnowledgeBase {
  if (!value || !validKnowledgeBaseId(value.id) || typeof value.name !== 'string' || typeof value.description !== 'string'
    || typeof value.demo !== 'boolean' || !Number.isFinite(Date.parse(value.createdAt)) || !Number.isFinite(Date.parse(value.updatedAt))
    || !Number.isSafeInteger(value.documentCount) || value.documentCount < 0 || !Number.isSafeInteger(value.imageCount) || value.imageCount < 0
    || (value.pendingCleanupCount !== undefined && (!Number.isSafeInteger(value.pendingCleanupCount) || value.pendingCleanupCount < 0))
    || (value.deletionPending !== undefined && typeof value.deletionPending !== 'boolean')) {
    throw new ApiError(0, 'INVALID_RESPONSE', '知识库响应无效，请重新检查后端状态。')
  }
  return { id: value.id, name: value.name, description: value.description, demo: value.demo,
    createdAt: value.createdAt, updatedAt: value.updatedAt, documentCount: value.documentCount, imageCount: value.imageCount,
    deletionPending: value.deletionPending === true, pendingCleanupCount: value.pendingCleanupCount ?? 0 }
}
function path(id: string) {
  if (!validKnowledgeBaseId(id)) throw new ApiError(400, 'INVALID_KNOWLEDGE_BASE_ID', '知识库 ID 格式无效，请从列表选择。')
  return `/api/knowledge-bases/${id}`
}
export async function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  const values = await request<KnowledgeBase[]>('/api/knowledge-bases')
  if (!Array.isArray(values)) throw new ApiError(0, 'INVALID_RESPONSE', '知识库列表响应无效，请重新检查后端状态。')
  return values.map(base)
}
export async function getKnowledgeBase(id: string): Promise<KnowledgeBase> { return base(await request(path(id))) }
export async function createKnowledgeBase(input: KnowledgeBaseInput): Promise<KnowledgeBase> {
  return base(await mutate('/api/knowledge-bases', JSON.stringify(input)))
}
export async function updateKnowledgeBase(id: string, input: KnowledgeBaseInput): Promise<KnowledgeBase> {
  return base(await mutate(path(id), JSON.stringify(input), 'application/json', true, 'PATCH'))
}
export async function deleteKnowledgeBase(id: string, expected?: KnowledgeBase): Promise<void> {
  const counts = expected ? `?expectedDocumentCount=${expected.documentCount}&expectedImageCount=${expected.imageCount}&expectedPendingCleanupCount=${expected.pendingCleanupCount ?? 0}` : ''
  await mutate(path(id) + counts, undefined, 'application/json', true, 'DELETE')
}
export function validateKnowledgeBase(input: KnowledgeBaseInput): string {
  const name = input.name.trim()
  if (!name || name.length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(name)) return '名称需为 1～100 个字符，不能全为空白或包含控制字符。'
  if (input.description.trim().length > 2000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(input.description)) return '简介最多 2000 个字符，不能包含非法控制字符。'
  return ''
}
