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
  let response: Response
  try {
    response = await fetch('/api/health', { cache: 'no-store', signal: AbortSignal.timeout(12000) })
  } catch {
    throw new Error('无法连接后端或请求超时，请确认后端已启动，然后重新检查。')
  }
  // 503 with a valid payload is a real database/extension failure, not a network error.
  if (response.status !== 200 && response.status !== 503) {
    throw new Error(`后端返回 HTTP ${response.status}，请检查后端启动日志。`)
  }
  const payload: unknown = await response.json().catch(() => null)
  if (!isHealth(payload)) {
    throw new Error('未收到有效的后端健康状态，请检查 API 代理及后端运行状态。')
  }
  return payload
}
