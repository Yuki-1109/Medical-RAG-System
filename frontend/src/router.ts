import { createRouter, createWebHistory } from 'vue-router'
import StartupView from './views/StartupView.vue'
import LoginView from './views/LoginView.vue'
import SetupView from './views/SetupView.vue'
import ConnectionView from './views/ConnectionView.vue'
import HomeView from './views/HomeView.vue'
import NotFoundView from './views/NotFoundView.vue'
import MainLayout from './layouts/MainLayout.vue'
import KnowledgeBasesView from './views/KnowledgeBasesView.vue'
import KnowledgeBaseView from './views/KnowledgeBaseView.vue'
import DocumentsView from './views/DocumentsView.vue'
import { auth, expireAuthentication, resolveAuthentication } from './auth/state'
import { safeDestination } from './auth/redirect'
import { onUnauthorized } from './api/client'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'entry', component: ConnectionView },
    { path: '/setup', name: 'setup', component: SetupView, meta: { guestOnly: true } },
    { path: '/login', name: 'login', component: LoginView, meta: { guestOnly: true } },
    { path: '/connection', name: 'connection', component: ConnectionView },
    { path: '/health', name: 'public-health', component: StartupView },
    { path: '/app', component: MainLayout, meta: { requiresAuth: true }, children: [
      { path: '', name: 'home', component: HomeView, meta: { title: '系统首页' } },
      { path: 'health', name: 'health', component: StartupView, meta: { title: '服务检查' } },
      { path: 'knowledge-bases', name: 'knowledge-bases', component: KnowledgeBasesView, meta: { title: '我的知识库' } },
      { path: 'knowledge-bases/:id', name: 'knowledge-base', component: KnowledgeBaseView, meta: { title: '知识库' } },
      { path: 'knowledge-bases/:id/documents', name: 'documents', component: DocumentsView, meta: { title: '文献管理' } },
      { path: 'knowledge-bases/:id/documents/:documentId', name: 'document-preview', component: () => import('./views/DocumentPreviewView.vue'), meta: { title: 'PDF 原文预览' } },
      { path: ':pathMatch(.*)*', component: NotFoundView },
    ] },
    { path: '/:pathMatch(.*)*', component: NotFoundView },
  ],
})

router.beforeEach(async (to) => {
  if (['connection', 'public-health'].includes(String(to.name))) return true
  try {
    await resolveAuthentication()
  } catch {
    return { name: 'connection', query: { redirect: safeDestination(to.fullPath) } }
  }
  if (!auth.initialized) return to.name === 'setup' ? true : { name: 'setup' }
  if (!auth.user) {
    return to.name === 'login' ? true : { name: 'login', query: { redirect: safeDestination(to.fullPath) } }
  }
  if (to.name === 'entry' || to.meta.guestOnly) return safeDestination(to.query.redirect)
  return true
})

onUnauthorized(() => {
  if (!auth.user) return
  expireAuthentication()
  if (router.currentRoute.value.meta.requiresAuth) {
    void router.replace({ name: 'login', query: { redirect: safeDestination(router.currentRoute.value.fullPath) } })
  }
})
