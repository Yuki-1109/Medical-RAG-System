import { ApiError, mutate, request, uploadForm } from './client'
import { validKnowledgeBaseId } from './knowledgeBases'
export interface ImageMetadata { imageType: 'LESION' | 'NORMAL' | 'UNCLASSIFIED'; stage: string; severity: string; keywords: string; note: string }
export interface ImageInfo extends ImageMetadata {
  id: string; knowledgeBaseId: string; originalFilename: string; fileFormat: 'JPEG' | 'PNG'; fileSize: number | null
  sha256: string | null; width: number | null; height: number | null; lifecycleStatus: 'UPLOADING' | 'ACTIVE' | 'DELETING' | 'DELETE_FAILED'
  cleanupError: string | null; createdAt: string; updatedAt: string
}
export interface ImageLimits { maxImageBytes: number; maxWidth: number; maxHeight: number; maxPixels: number }
function id(value: string) { if (!validKnowledgeBaseId(value)) throw new ApiError(400, 'INVALID_IMAGE_ID', '图片 ID 无效，请从当前知识库选择。'); return value }
function image(value: ImageInfo): ImageInfo {
  if (!value || !validKnowledgeBaseId(value.id) || !validKnowledgeBaseId(value.knowledgeBaseId) || typeof value.originalFilename !== 'string'
    || !['JPEG', 'PNG'].includes(value.fileFormat) || !['LESION', 'NORMAL', 'UNCLASSIFIED'].includes(value.imageType)
    || !['UPLOADING', 'ACTIVE', 'DELETING', 'DELETE_FAILED'].includes(value.lifecycleStatus)
    || !['stage', 'severity', 'keywords', 'note'].every(field => typeof value[field as keyof ImageMetadata] === 'string')
    || !Number.isFinite(Date.parse(value.createdAt)) || !Number.isFinite(Date.parse(value.updatedAt))
    || [value.fileSize, value.width, value.height].some(number => number !== null && (!Number.isSafeInteger(number) || number <= 0))
    || (value.sha256 !== null && !/^[0-9a-f]{64}$/.test(value.sha256))
    || (value.lifecycleStatus === 'ACTIVE' && (!value.fileSize || !value.width || !value.height || !value.sha256))) throw new ApiError(0, 'INVALID_RESPONSE', '图片响应无效，请检查后端。')
  return value
}
export async function listImages(base: string) {
  const values = await request<ImageInfo[]>(`/api/knowledge-bases/${id(base)}/images`)
  if (!Array.isArray(values)) throw new ApiError(0, 'INVALID_RESPONSE', '图片列表无效，请重试。')
  const result = values.map(image)
  if (result.some(row => row.knowledgeBaseId !== base)) throw new ApiError(0, 'INVALID_RESPONSE', '图片与所选知识库不一致，请重新加载。')
  return result
}
export async function getImage(imageId: string) { return image(await request<ImageInfo>(`/api/images/${id(imageId)}`)) }
export async function imageLimits() {
  const limits = await request<ImageLimits>('/api/images/upload-limits')
  if (!limits || ![limits.maxImageBytes, limits.maxWidth, limits.maxHeight, limits.maxPixels].every(value => Number.isSafeInteger(value) && value > 0)) throw new ApiError(0, 'INVALID_RESPONSE', '图片限制配置无效，请检查后端。')
  return limits
}
export async function uploadImage(base: string, file: File, progress: (value: number) => void, signal: AbortSignal) {
  const body = new FormData(); body.set('file', file)
  const result = image(await uploadForm<ImageInfo>(`/api/knowledge-bases/${id(base)}/images`, body, progress, signal))
  if (result.knowledgeBaseId !== base) throw new ApiError(0, 'INVALID_RESPONSE', '上传结果与所选知识库不一致，请刷新检查。')
  return result
}
export function originalImage(imageId: string, download = false, signal?: AbortSignal) {
  return request<Blob>(`/api/images/${id(imageId)}/${download ? 'download' : 'content'}`, { responseType: 'blob', mediaTypes: ['image/jpeg', 'image/png'], signal: signal ?? AbortSignal.timeout(120000) })
}
export async function updateImage(imageId: string, input: ImageMetadata) { return image(await mutate<ImageInfo>(`/api/images/${id(imageId)}`, JSON.stringify(input), 'application/json', true, 'PATCH')) }
export async function deleteImage(imageId: string) { await mutate(`/api/images/${id(imageId)}`, undefined, 'application/json', true, 'DELETE') }
export function validateImageFile(file: File, maxBytes: number) {
  if (!/\.(jpe?g|png)$/i.test(file.name) || /[\\/:\u0000-\u001f\u007f-\u009f]/.test(file.name)) return '请选择名称有效的 JPG、JPEG 或 PNG，不能包含路径或控制字符。'
  if (!file.size) return '空图片不能上传。'
  if (file.size > maxBytes) return '图片超过本机大小上限，请缩小文件。'
  return ''
}
export function validateImageMetadata(value: ImageMetadata) {
  if (!['LESION', 'NORMAL', 'UNCLASSIFIED'].includes(value.imageType)) return '请选择有效的人工类型。'
  for (const [key, max] of [['stage', 100], ['severity', 100], ['keywords', 500], ['note', 2000]] as const) {
    if (value[key].trim().length > max || /[<>\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(value[key])) return '人工信息超出长度限制或包含 HTML、脚本、非法控制字符，请修改。'
  }
  return ''
}
export function imageTypeLabel(value: ImageMetadata['imageType']) { return { LESION: '病灶', NORMAL: '正常对照', UNCLASSIFIED: '未分类' }[value] }
