<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton } from 'element-plus'
import { auth, logout, verifySession } from '../auth/state'
import { onApiActivity } from '../api/client'

const route = useRoute()
const router = useRouter()
const busy = ref(false)
const error = ref('')
const laterModules = ['我的知识库', '文献管理', '片段编辑', '医学图片', 'AI 智能问答', '历史会话', '系统设置']
let idleTimer: ReturnType<typeof setTimeout> | undefined

async function checkSession() {
  if (!auth.user || busy.value) return
  try { await verifySession(); error.value = '' }
  catch (cause) { if (auth.user) error.value = cause instanceof Error ? cause.message : '无法检查登录状态。' }
}
function scheduleIdleCheck() {
  clearTimeout(idleTimer)
  // One check after the server idle limit; do not repeatedly poll and keep idle sessions alive.
  idleTimer = setTimeout(() => { void checkSession() }, 30 * 60 * 1000 + 5000)
}
async function signOut() {
  busy.value = true
  error.value = ''
  try { await logout(); await router.replace({ name: 'login' }) }
  catch (cause) { if (auth.user) error.value = cause instanceof Error ? cause.message : '退出失败，请重试。' }
  finally { busy.value = false }
}
onMounted(() => {
  scheduleIdleCheck()
  onApiActivity(scheduleIdleCheck)
  window.addEventListener('focus', checkSession)
})
onBeforeUnmount(() => {
  clearTimeout(idleTimer)
  onApiActivity(() => {})
  window.removeEventListener('focus', checkSession)
})
</script>

<template>
  <div class="system-layout">
    <aside class="system-sidebar">
      <div class="sidebar-brand"><span class="brand-mark" aria-hidden="true">M</span><div><strong>医疗 RAG</strong><small>研究资料工作台</small></div></div>
      <p class="sidebar-label">当前功能</p>
      <nav aria-label="系统导航">
        <router-link to="/app" :class="{ selected: route.path === '/app' }">系统首页</router-link>
        <router-link to="/app/health" :class="{ selected: route.path === '/app/health' }">服务检查</router-link>
        <p class="sidebar-label">后续模块</p>
        <span v-for="name in laterModules" :key="name" class="pending-navigation" aria-disabled="true">{{ name }}<small>待开发</small></span>
      </nav>
      <div class="sidebar-footer">本机独立部署<br />医疗研究资料辅助工具</div>
    </aside>
    <div class="system-body">
      <header class="system-header">
        <h1>{{ route.meta.title || '研究资料工作台' }}</h1>
        <div class="account-controls"><span class="account-name">{{ auth.user?.username }}</span><el-button :loading="busy" @click="signOut">退出登录</el-button></div>
      </header>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="layout-error" show-icon />
      <section class="system-content"><router-view /></section>
    </div>
  </div>
</template>
