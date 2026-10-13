import { ApiError, request } from './client'

export type HealthStatus = 'UP' | 'DOWN' | 'UNKNOWN'
export interface ComponentHealth {
  status: HealthStatus
  message: string
  version: string | null
}
export interface HealthResponse {
  status: HealthStatus
  checkedAt: string
  backend: ComponentHealth
  database: ComponentHealth
  pgvector: ComponentHealth
  migrations: ComponentHealth
}

function isComponent(value: unknown): value is ComponentHealth {
  if (!value || typeof value !== 'object') return false
  const component = value as Record<string, unknown>
  return ['UP', 'DOWN', 'UNKNOWN'].includes(String(component.status))
    && typeof component.message === 'string'
    && (component.version === null || typeof component.version === 'string')
}

function isHealth(value: unknown): value is HealthResponse {
  if (!value || typeof value !== 'object') return false
  const health = value as Record<string, unknown>
  return ['UP', 'DOWN'].includes(String(health.status))
    && typeof health.checkedAt === 'string'
    && ['backend', 'database', 'pgvector', 'migrations'].every((key) => isComponent(health[key]))
}

export async function fetchHealth(): Promise<HealthResponse> {
  // 503 with a valid payload is a real database/extension failure, not a network error.
  const payload: unknown = await request('/api/health', { acceptedStatuses: [200, 503], notifyUnauthorized: false })
  if (!isHealth(payload)) {
    throw new ApiError(0, 'INVALID_RESPONSE', '未收到有效的后端健康状态，请检查 API 代理及后端运行状态。')
  }
  return payload
}
