<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElEmpty, ElTag } from 'element-plus'
import KnowledgeBaseForm from '../components/KnowledgeBaseForm.vue'
import { createKnowledgeBase, listKnowledgeBases, updateKnowledgeBase, type KnowledgeBase, type KnowledgeBaseInput } from '../api/knowledgeBases'
import { knowledgeBaseContext, clearCurrentKnowledgeBase } from '../knowledgebase/context'
import { confirmDeleteKnowledgeBase } from '../knowledgebase/actions'

const router = useRouter()
const bases = ref<KnowledgeBase[]>([])
const loading = ref(false)
const error = ref('')
const deleting = ref('')
const formOpen = ref(false)
const editing = ref<KnowledgeBase | null>(null)
const saving = ref(false)
const formError = ref('')
async function load() {
  loading.value = true; error.value = ''
  try {
    bases.value = await listKnowledgeBases()
    if (knowledgeBaseContext.current && !bases.value.some(base => base.id === knowledgeBaseContext.current?.id)) clearCurrentKnowledgeBase()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法加载知识库列表。' }
  finally { loading.value = false }
}
function openForm(base: KnowledgeBase | null) { editing.value = base; formError.value = ''; formOpen.value = true }
async function save(input: KnowledgeBaseInput) {
  saving.value = true; formError.value = ''
  try {
    const base = editing.value ? await updateKnowledgeBase(editing.value.id, input) : await createKnowledgeBase(input)
    if (knowledgeBaseContext.current?.id === base.id) knowledgeBaseContext.current = base
    formOpen.value = false
    await load()
  } catch (cause) { formError.value = cause instanceof Error ? cause.message : '保存失败，请重试。' }
  finally { saving.value = false }
}
async function remove(base: KnowledgeBase) {
  if (deleting.value) return
  deleting.value = base.id; error.value = ''
  try { if (await confirmDeleteKnowledgeBase(base)) await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '删除未完成，请重新检查。' }
  finally { deleting.value = '' }
}
onMounted(load)
</script>

<template>
  <main class="knowledge-bases-page">
    <div class="page-header"><div><p class="eyebrow">独立管理研究资料</p><h2>我的知识库</h2><p class="subtitle">创建不同主题的知识库，并选择当前使用的知识库。</p></div><div class="library-header-actions"><el-button :loading="loading" @click="load">刷新列表</el-button><el-button type="primary" @click="openForm(null)">新建知识库</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-alert" />
    <p v-if="loading" role="status">正在加载知识库…</p>
    <el-empty v-else-if="!error && !bases.length" description="还没有知识库，创建第一个研究资料库吧。"><el-button type="primary" @click="openForm(null)">创建第一个知识库</el-button></el-empty>
    <div v-else-if="!loading && !error" class="knowledge-base-grid">
      <el-card v-for="base in bases" :key="base.id" class="knowledge-base-card" shadow="never" :data-library-id="base.id">
        <div class="library-card-heading"><h3>{{ base.name }}</h3><el-tag v-if="base.demo">演示库</el-tag><el-tag v-if="knowledgeBaseContext.current?.id === base.id" type="success">当前库</el-tag></div>
        <p class="library-description">{{ base.description || '暂无简介' }}</p>
        <p class="library-counts">文献 {{ base.documentCount }} · 图片 {{ base.imageCount }}</p>
        <p class="library-meta">ID：{{ base.id }}<br />更新于 {{ new Date(base.updatedAt).toLocaleString('zh-CN') }}</p>
        <div class="library-card-actions"><el-button type="primary" @click="router.push(`/app/knowledge-bases/${base.id}`)">进入知识库</el-button><el-button @click="openForm(base)">编辑</el-button><el-button type="danger" plain :loading="deleting === base.id" :disabled="!!deleting && deleting !== base.id" @click="remove(base)">删除</el-button></div>
      </el-card>
    </div>
    <p class="library-stage-note">当前支持知识库管理；文献与图片上传尚未开放，知识库中的资料数量为真实空库数量。</p>
    <KnowledgeBaseForm :open="formOpen" :editing="editing" :busy="saving" :error="formError" @close="formOpen = false" @save="save" />
  </main>
</template>
