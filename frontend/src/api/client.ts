export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions extends RequestInit {
  acceptedStatuses?: number[]
  notifyUnauthorized?: boolean
  responseType?: 'json' | 'blob'
  mediaTypes?: string[]
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
  const { acceptedStatuses = [200, 201, 204], notifyUnauthorized = true, responseType = 'json', mediaTypes = ['application/pdf'], ...init } = options
  const headers = new Headers(init.headers)
  headers.set('Accept', responseType === 'blob' ? mediaTypes.join(', ') : 'application/json')
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
  return decode<T>(response, acceptedStatuses, notifyUnauthorized, responseType, mediaTypes)
}

async function decode<T>(response: Response, acceptedStatuses = [200, 201, 204], notifyUnauthorized = true, responseType: 'json' | 'blob' = 'json', mediaTypes = ['application/pdf']): Promise<T> {
  if (acceptedStatuses.includes(response.status) && responseType === 'blob') {
    if (!mediaTypes.includes(response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() ?? '')) {
      throw new ApiError(0, 'INVALID_RESPONSE', '后端未返回有效的资料原件，请重新检查。')
    }
    let original: Blob
    try { original = await response.blob() }
    catch { throw new ApiError(0, 'NETWORK_UNAVAILABLE', '资料原件传输中断，请检查后端和连接后重新加载。') }
    if (!original.size) throw new ApiError(0, 'INVALID_RESPONSE', '资料原件为空，请检查存储状态。')
    activityHandler?.()
    return original as T
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

/** Real multipart byte progress; keep Session/CSRF and common error/expiry handling. */
export async function uploadForm<T>(path: string, body: FormData, progress: (percent: number) => void, signal: AbortSignal): Promise<T> {
  if (!path.startsWith('/api/') || path.includes('://')) throw new Error('API 请求必须使用本机相对地址')
  async function send(): Promise<T> {
    const token = await getCsrf()
    if (signal.aborted) throw new DOMException('Upload aborted', 'AbortError')
    return new Promise<T>((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      const abort = () => xhr.abort()
      const finish = () => signal.removeEventListener('abort', abort)
      xhr.open('POST', path)
      xhr.withCredentials = true
      xhr.timeout = 120000
      xhr.setRequestHeader('Accept', 'application/json')
      xhr.setRequestHeader('X-CSRF-TOKEN', token)
      xhr.upload.onprogress = event => { if (event.lengthComputable) progress(Math.min(100, Math.round(event.loaded / event.total * 100))) }
      xhr.onload = () => {
        finish()
        const response = new Response(xhr.responseText, { status: xhr.status, headers: { 'Content-Type': xhr.getResponseHeader('Content-Type') || '' } })
        void decode<T>(response).then(resolve, reject)
      }
      xhr.onerror = xhr.ontimeout = () => { finish(); reject(new ApiError(0, 'NETWORK_UNAVAILABLE', '上传连接中断或超时，请刷新列表核对结果后再重试，避免重复上传。')) }
      xhr.onabort = () => { finish(); reject(new DOMException('Upload aborted', 'AbortError')) }
      signal.addEventListener('abort', abort, { once: true })
      xhr.send(body) // Browser supplies its own multipart boundary; never set Content-Type.
    })
  }
  try { return await send() }
  catch (error) {
    if (!(error instanceof ApiError) || error.code !== 'CSRF_INVALID' || signal.aborted) throw error
    await getCsrf(true)
    return send()
  }
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

export async function mutate<T>(path: string, body?: string, contentType = 'application/json', notifyUnauthorized = true, method: 'POST' | 'PUT' | 'PATCH' | 'DELETE' = 'POST'): Promise<T> {
  async function send() {
    return request<T>(path, {
      method, body, notifyUnauthorized,
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
