import { getCsrf, mutate, request } from './client'

export type ModelType = 'CHAT' | 'EMBEDDING'
export interface ModelInfo {
  type: ModelType
  protocol: string
  baseUrl: string
  modelId: string
  keyConfigured: boolean
  configured: boolean
  testStatus: 'UNTESTED' | 'SUCCESS' | 'FAILED'
  testCode: string | null
  testMessage: string | null
  testedAt: string | null
  embeddingDimension: number | null
  revision: number
}
export interface ModelInput { type: ModelType; protocol: string; baseUrl: string; modelId: string; apiKey: string; deleteKey: boolean }
export const listModels = () => request<ModelInfo[]>('/api/settings/models')
export const saveModel = (input: ModelInput) => mutate<ModelInfo>('/api/settings/models', JSON.stringify(input), 'application/json', true, 'PUT')
export async function testModel(type: ModelType): Promise<ModelInfo> {
  // Backend has a 15-second total deadline. Do not use the generic 12-second timeout for this call.
  return request<ModelInfo>('/api/settings/models/test', { method: 'POST', body: JSON.stringify({ type }),
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': await getCsrf() }, signal: AbortSignal.timeout(20000) })
}
export function modelStatus(info: ModelInfo): string {
  if (!info.configured) return '未配置'
  if (info.testStatus === 'SUCCESS') return '连接测试成功'
  if (info.testStatus === 'FAILED') return '测试失败'
  return '已配置，尚未验证'
}
