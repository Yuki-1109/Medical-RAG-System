export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions extends RequestInit {
  acceptedStatuses?: number[]
  notifyUnauthorized?: boolean
}

let unauthorizedHandler: (() => void) | undefined
let activityHandler: (() => void) | undefined
let csrfToken: string | null = null
let csrfPending: Promise<string> | null = null

export function onUnauthorized(handler: () => void) { unauthorizedHandler = handler }
export function onApiActivity(handler: () => void) { activityHandler = handler }
export function clearCsrf() { csrfToken = null; csrfPending = null }

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!path.startsWith('/api/') || path.includes('://')) throw new Error('API 请求必须使用本机相对地址')
  const { acceptedStatuses = [200, 201, 204], notifyUnauthorized = true, ...init } = options
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  let response: Response
  try {
    response = await fetch(path, {
      ...init, credentials: 'same-origin', cache: 'no-store',
      headers,
      signal: init.signal ?? AbortSignal.timeout(12000),
    })
  } catch {
    throw new ApiError(0, 'NETWORK_UNAVAILABLE', '无法连接后端或请求超时，请确认后端已启动，然后重新检查。')
  }
  const payload: unknown = response.status === 204 ? undefined : await response.json().catch(() => null)
  if (!acceptedStatuses.includes(response.status)) {
    const error = payload && typeof payload === 'object' ? payload as Record<string, unknown> : {}
    const code = typeof error.code === 'string' ? error.code : 'HTTP_ERROR'
    if (response.status === 401 && code === 'AUTHENTICATION_REQUIRED' && notifyUnauthorized) {
      clearCsrf()
      unauthorizedHandler?.()
    }
    const message = typeof error.message === 'string' ? error.message
      : response.status >= 500 ? '后端暂时不可用，请检查服务并重试。' : `请求失败（HTTP ${response.status}），请重试。`
    throw new ApiError(response.status, code, message)
  }
  if (response.status !== 204 && payload === null) {
    throw new ApiError(response.status, 'INVALID_RESPONSE', '未收到有效的后端响应，请检查 API 代理及后端运行状态。')
  }
  activityHandler?.()
  return payload as T
}

export async function getCsrf(force = false): Promise<string> {
  if (force) clearCsrf()
  if (csrfToken) return csrfToken
  if (csrfPending) return csrfPending
  csrfPending = request<{ headerName: string; token: string }>('/api/auth/csrf', { notifyUnauthorized: false })
    .then((result) => {
      if (result.headerName !== 'X-CSRF-TOKEN' || typeof result.token !== 'string' || !result.token) {
        throw new ApiError(0, 'INVALID_RESPONSE', '无法获取安全令牌，请重新连接后端。')
      }
      csrfToken = result.token
      return result.token
    }).finally(() => { csrfPending = null })
  return csrfPending
}

export async function mutate<T>(path: string, body?: string, contentType = 'application/json', notifyUnauthorized = true): Promise<T> {
  async function send() {
    return request<T>(path, {
      method: 'POST', body, notifyUnauthorized,
      headers: { 'Content-Type': contentType, 'X-CSRF-TOKEN': await getCsrf() },
    })
  }
  try {
    return await send()
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 'CSRF_INVALID') throw error
    // A2 rejects CSRF before the action runs; only this rejection is safe to retry once.
    await getCsrf(true)
    return send()
  }
}
