<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElEmpty, ElTag } from 'element-plus'
import KnowledgeBaseForm from '../components/KnowledgeBaseForm.vue'
import { updateKnowledgeBase, type KnowledgeBaseInput } from '../api/knowledgeBases'
import { knowledgeBaseContext as context, loadCurrentKnowledgeBase, clearCurrentKnowledgeBase } from '../knowledgebase/context'
import { confirmDeleteKnowledgeBase } from '../knowledgebase/actions'
const route = useRoute()
const router = useRouter()
const formOpen = ref(false)
const saving = ref(false)
const deleting = ref(false)
const error = ref('')
const formError = ref('')
watch(() => route.params.id, id => { error.value = ''; formOpen.value = false; void loadCurrentKnowledgeBase(String(id)) }, { immediate: true })
onBeforeUnmount(() => { if (!context.current) clearCurrentKnowledgeBase() })
async function save(input: KnowledgeBaseInput) {
  if (!context.current) return
  const id = context.current.id
  saving.value = true; formError.value = ''
  try { const base = await updateKnowledgeBase(id, input); if (String(route.params.id) === id) context.current = base; formOpen.value = false }
  catch (cause) { formError.value = cause instanceof Error ? cause.message : '保存失败，请重试。' }
  finally { saving.value = false }
}
async function remove() {
  if (!context.current || deleting.value) return
  const base = context.current
  deleting.value = true; error.value = ''
  try { if (await confirmDeleteKnowledgeBase(base)) await router.replace('/app/knowledge-bases') }
  catch (cause) { const message = cause instanceof Error ? cause.message : '删除未完成，请重试。'; await loadCurrentKnowledgeBase(base.id, true); error.value = message }
  finally { deleting.value = false }
}
</script>

<template>
  <main class="knowledge-base-detail">
    <router-link to="/app/knowledge-bases" class="subtle-link">← 返回我的知识库</router-link>
    <p v-if="context.loading" role="status">正在核验并加载当前知识库…</p>
    <template v-else-if="context.error"><el-alert :title="context.error" type="error" :closable="false" show-icon class="form-alert" /><el-button @click="loadCurrentKnowledgeBase(String(route.params.id))">重新检查</el-button></template>
    <template v-else-if="context.current">
      <div class="page-header"><div><p class="eyebrow">当前知识库 · ID：{{ context.current.id }}</p><h2>{{ context.current.name }}</h2></div><div class="library-header-actions"><el-tag v-if="context.current.demo">演示库</el-tag><el-button :disabled="context.current.deletionPending" @click="formError = ''; formOpen = true">编辑知识库</el-button><el-button type="danger" plain :loading="deleting" @click="remove">删除知识库</el-button></div></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-alert" />
      <el-card shadow="never"><p class="library-description">{{ context.current.description || '暂无简介' }}</p><p class="library-counts">文献 {{ context.current.documentCount }} · 图片 {{ context.current.imageCount }}</p><p class="library-meta">创建于 {{ new Date(context.current.createdAt).toLocaleString('zh-CN') }}<br />更新于 {{ new Date(context.current.updatedAt).toLocaleString('zh-CN') }}</p></el-card>
      <el-alert v-if="context.current.deletionPending" title="知识库删除未完成，资料不可访问，请再次删除以重试清理。" type="warning" :closable="false" show-icon />
      <p v-if="context.current.pendingCleanupCount" class="document-warning">另有 {{ context.current.pendingCleanupCount }} 条上传未完成或待清理记录。</p>
      <div class="library-entry-actions">
        <el-button :disabled="context.current.deletionPending" @click="router.push(`/app/knowledge-bases/${context.current.id}/documents`)">进入文献管理</el-button>
        <el-button :disabled="context.current.deletionPending" @click="router.push(`/app/knowledge-bases/${context.current.id}/images`)">进入医学图片</el-button>
      </div>
      <el-empty v-if="!context.current.documentCount && !context.current.imageCount && !context.current.pendingCleanupCount" description="当前知识库为空，可以上传 PDF 或 JPG/PNG 原件。" />
      <KnowledgeBaseForm :open="formOpen" :editing="context.current" :busy="saving" :error="formError" @close="formOpen = false" @save="save" />
    </template>
  </main>
</template>
