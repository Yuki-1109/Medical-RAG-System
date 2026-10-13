import { reactive } from 'vue'
import { ApiError, clearCsrf, getCsrf, mutate, request } from '../api/client'

export interface Administrator { id: number; username: string; createdAt: string }
export const auth = reactive({
  user: null as Administrator | null,
  initialized: null as boolean | null,
  loading: false,
  connectionError: '',
  notice: '',
  suggestedUsername: '',
})
let pending: Promise<void> | null = null
let revision = 0

function administrator(value: Administrator): Administrator {
  if (!value || typeof value.id !== 'number' || typeof value.username !== 'string' || typeof value.createdAt !== 'string') {
    throw new ApiError(0, 'INVALID_RESPONSE', '账号状态响应无效，请重新连接后端。')
  }
  // Only the public profile is kept in memory; never copy arbitrary response fields.
  return { id: value.id, username: value.username, createdAt: value.createdAt }
}

export async function resolveAuthentication(): Promise<void> {
  if (pending) return pending
  const currentRevision = revision
  auth.loading = true
  auth.connectionError = ''
  pending = (async () => {
    const state = await request<{ initialized: boolean }>('/api/auth/setup-status', { notifyUnauthorized: false })
    if (typeof state.initialized !== 'boolean') throw new ApiError(0, 'INVALID_RESPONSE', '初始化状态响应无效，请重新连接后端。')
    let user: Administrator | null = null
    if (state.initialized) {
      try {
        user = administrator(await request<Administrator>('/api/auth/me', { notifyUnauthorized: false }))
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401 || error.code !== 'AUTHENTICATION_REQUIRED') throw error
      }
    }
    if (currentRevision === revision) {
      if (auth.user && !user) expireAuthentication()
      auth.initialized = state.initialized
      auth.user = user
    }
  })().catch((error) => {
    auth.connectionError = error instanceof Error ? error.message : '无法确认登录状态，请重新连接。'
    throw error
  }).finally(() => { pending = null; auth.loading = false })
  return pending
}

export function expireAuthentication() {
  revision++
  auth.user = null
  clearCsrf()
  auth.notice = '登录会话已失效，请重新登录。'
}

export async function setupAdministrator(username: string, password: string) {
  const created = administrator(await mutate<Administrator>('/api/auth/setup', JSON.stringify({ username, password }), 'application/json', false))
  revision++
  auth.initialized = true
  auth.user = null
  auth.suggestedUsername = created.username
  auth.notice = '管理员创建成功，请使用刚设置的账号登录。'
}

export async function login(username: string, password: string) {
  const user = administrator(await mutate<Administrator>('/api/auth/login', new URLSearchParams({ username, password }).toString(), 'application/x-www-form-urlencoded', false))
  clearCsrf()
  await getCsrf(true)
  revision++
  auth.user = user
  auth.initialized = true
  auth.notice = ''
  auth.suggestedUsername = user.username
}

export async function logout() {
  await mutate<void>('/api/auth/logout')
  revision++
  auth.user = null
  clearCsrf()
  auth.notice = '已安全退出登录。'
}

export async function verifySession() {
  if (!auth.user) return
  const currentRevision = revision
  const user = administrator(await request<Administrator>('/api/auth/me'))
  if (currentRevision === revision) auth.user = user
}
