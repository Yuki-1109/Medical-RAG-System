<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElOption, ElSelect } from 'element-plus'
import { auth, logout, verifySession } from '../auth/state'
import { onApiActivity } from '../api/client'
import { knowledgeBaseContext, loadCurrentKnowledgeBase, clearCurrentKnowledgeBase } from '../knowledgebase/context'
import { listKnowledgeBases, type KnowledgeBase } from '../api/knowledgeBases'

const route = useRoute()
const router = useRouter()
const busy = ref(false)
const error = ref('')
const laterModules = ['片段编辑', '医学图片', 'AI 智能问答', '历史会话', '系统设置']
const options = ref<KnowledgeBase[]>([])
const availableOptions = computed(() => knowledgeBaseContext.current
  ? [knowledgeBaseContext.current, ...options.value.filter(base => base.id !== knowledgeBaseContext.current?.id)] : options.value)
const optionsLoading = ref(false)
async function loadOptions(open: boolean) {
  if (!open) return
  optionsLoading.value = true
  try {
    options.value = await listKnowledgeBases()
    if (knowledgeBaseContext.current && !options.value.some(base => base.id === knowledgeBaseContext.current?.id)) {
      clearCurrentKnowledgeBase()
      knowledgeBaseContext.error = '原知识库已删除或无法访问，请重新选择。'
    }
    error.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法加载可切换的知识库。' }
  finally { optionsLoading.value = false }
}
let idleTimer: ReturnType<typeof setTimeout> | undefined

async function checkSession() {
  if (!auth.user || busy.value) return
  try {
    await verifySession(); error.value = ''
    if (knowledgeBaseContext.current) await loadCurrentKnowledgeBase(knowledgeBaseContext.current.id, true)
  }
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
        <router-link to="/app/knowledge-bases" :class="{ selected: route.path.startsWith('/app/knowledge-bases') }">我的知识库</router-link>
        <router-link :to="knowledgeBaseContext.current ? `/app/knowledge-bases/${knowledgeBaseContext.current.id}/documents` : '/app/knowledge-bases'" :class="{ selected: route.name === 'documents' || route.name === 'document-preview' }">文献管理</router-link>
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
      <div class="current-library-bar">
        <span class="current-library-label">当前知识库：{{ knowledgeBaseContext.loading ? '正在核验…' : knowledgeBaseContext.current?.name || '尚未选择' }}<small v-if="knowledgeBaseContext.current">ID：{{ knowledgeBaseContext.current.id }}</small></span>
        <el-select aria-label="切换知识库" placeholder="选择或切换知识库" :model-value="knowledgeBaseContext.current?.id" :loading="optionsLoading" @visible-change="loadOptions" @change="(id: string) => router.push(`/app/knowledge-bases/${id}${route.name === 'documents' || route.name === 'document-preview' ? '/documents' : ''}`)">
          <el-option v-for="base in availableOptions" :key="base.id" :label="base.name" :value="base.id" />
        </el-select>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="layout-error" show-icon />
      <section class="system-content"><router-view /></section>
    </div>
  </div>
</template>
