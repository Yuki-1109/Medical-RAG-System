<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElEmpty, ElProgress, ElTag } from 'element-plus'
import { listDocuments, uploadDocument, uploadLimits, validatePdf, formatBytes, processingLabel, type DocumentInfo } from '../api/documents'
import { knowledgeBaseContext as context, loadCurrentKnowledgeBase } from '../knowledgebase/context'
import { confirmDeleteDocument, downloadOriginal } from '../document/actions'
const route = useRoute(), router = useRouter()
const rows = ref<DocumentInfo[]>([]), loading = ref(false), error = ref(''), uploadBusy = ref(false), progress = ref(0)
const maxBytes = ref<number | null>(null), selected = ref<File | null>(null), input = ref<HTMLInputElement | null>(null)
const deleting = ref(''), downloading = ref('')
let generation = 0, upload: AbortController | undefined
async function load() {
  const own = ++generation, base = String(route.params.id)
  rows.value = []; error.value = ''; loading.value = true
  await loadCurrentKnowledgeBase(base)
  if (own !== generation || !context.current) { if (own === generation) loading.value = false; return }
  try {
    const [values, limit] = await Promise.all([listDocuments(base), uploadLimits()])
    if (own === generation) { rows.value = values; maxBytes.value = limit }
  } catch (cause) { if (own === generation) error.value = cause instanceof Error ? cause.message : '文献加载失败，请重试。' }
  finally { if (own === generation) loading.value = false }
}
watch(() => route.params.id, () => {
  upload?.abort(); uploadBusy.value = false; selected.value = null; maxBytes.value = null
  if (input.value) input.value.value = ''
  void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++; upload?.abort() })
function choose(event: Event) {
  selected.value = (event.target as HTMLInputElement).files?.[0] ?? null; error.value = ''
  if (selected.value && maxBytes.value) error.value = validatePdf(selected.value, maxBytes.value)
}
async function send() {
  if (!selected.value || !context.current || !maxBytes.value || uploadBusy.value) return
  error.value = validatePdf(selected.value, maxBytes.value); if (error.value) return
  const base = context.current.id, own = generation
  upload = new AbortController(); const controller = upload
  uploadBusy.value = true; progress.value = 0
  try {
    await uploadDocument(base, selected.value, value => { if (own === generation) progress.value = value }, controller.signal)
    if (own === generation && String(route.params.id) === base) {
      selected.value = null; if (input.value) input.value.value = ''
      await load()
    }
  } catch (cause) {
    if (own === generation && !controller.signal.aborted) {
      const message = cause instanceof Error ? cause.message : '上传失败，请重试。'
      await load(); error.value = message
    }
  } finally { if (upload === controller) uploadBusy.value = false }
}
async function remove(document: DocumentInfo) {
  if (deleting.value) return
  const base = String(route.params.id), own = generation
  deleting.value = document.id; error.value = ''
  try { if (await confirmDeleteDocument(document) && String(route.params.id) === base) await load() }
  catch (cause) {
    if (own === generation) { const message = cause instanceof Error ? cause.message : '清理未完成，请重试。'; await load(); error.value = message }
  } finally { deleting.value = '' }
}
async function download(document: DocumentInfo) {
  if (downloading.value) return
  downloading.value = document.id; error.value = ''
  try { await downloadOriginal(document) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '下载失败，请重试。' }
  finally { downloading.value = '' }
}
</script>

<template>
  <main class="documents-page">
    <router-link :to="`/app/knowledge-bases/${route.params.id}`" class="subtle-link">← 返回知识库详情</router-link>
    <div class="page-header"><div><p class="eyebrow">当前知识库 · ID：{{ route.params.id }}</p><h2>文献管理</h2><p v-if="context.current" class="subtitle">{{ context.current.name }} · 文献 {{ context.current.documentCount }}</p></div><el-button :disabled="uploadBusy" :loading="loading" @click="load">刷新文献</el-button></div>
    <el-alert v-if="context.error" :title="context.error" type="error" :closable="false" show-icon />
    <template v-if="context.current">
      <el-alert v-if="context.current.deletionPending" title="知识库删除未完成，资料已停止访问。请返回知识库详情重试删除。" type="warning" :closable="false" show-icon />
      <section class="document-upload" aria-label="PDF 上传">
        <label for="pdf-file">选择 PDF 文件</label><input id="pdf-file" ref="input" type="file" accept=".pdf,application/pdf" :disabled="uploadBusy || !maxBytes || context.current.deletionPending" @change="choose" />
        <el-button type="primary" :loading="uploadBusy" :disabled="!selected || !!error || !maxBytes || context.current.deletionPending" @click="send">开始上传</el-button>
        <p v-if="maxBytes" class="library-stage-note">上限 {{ formatBytes(maxBytes) }}。原件保持不变；上传后为“待处理”，文本解析与索引尚未开放。</p>
        <div v-if="uploadBusy" role="status"><el-progress :percentage="progress" /><span>{{ progress === 100 ? '传输完成，正在校验并保存…' : `正在上传 ${progress}%…` }}</span></div>
      </section>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-alert" />
      <p v-if="loading" role="status">正在加载真实文献记录…</p>
      <el-empty v-else-if="!error && !rows.length" description="当前知识库还没有文献，请上传 PDF 原件。" />
      <div v-else class="document-list">
        <article v-for="document in rows" :key="document.id" class="document-row" :data-document-id="document.id">
          <div class="document-row-info"><h3>{{ document.originalFilename }}</h3><p>{{ formatBytes(document.fileSize) }} · {{ document.pageCount === null ? '页数尚未确认' : `${document.pageCount} 页` }} · {{ new Date(document.createdAt).toLocaleString('zh-CN') }}</p>
            <el-tag v-if="document.lifecycleStatus === 'ACTIVE'">{{ processingLabel(document.processingStatus) }}</el-tag>
            <el-tag v-else type="warning">{{ document.lifecycleStatus === 'UPLOADING' ? '上传未完成，可清理' : document.lifecycleStatus === 'DELETING' ? '待清理，可重试' : '清理失败，可重试' }}</el-tag>
            <p v-if="document.cleanupError || document.errorReason" class="document-warning">{{ document.cleanupError || document.errorReason }}</p>
          </div>
          <div class="document-row-actions">
            <el-button v-if="document.lifecycleStatus === 'ACTIVE' && !context.current.deletionPending" @click="router.push(`/app/knowledge-bases/${route.params.id}/documents/${document.id}?page=1`)">查看原文</el-button>
            <el-button v-if="document.lifecycleStatus === 'ACTIVE' && !context.current.deletionPending" :loading="downloading === document.id" @click="download(document)">下载原件</el-button>
            <el-button type="danger" plain :disabled="uploadBusy || (!!deleting && deleting !== document.id)" :loading="deleting === document.id" @click="remove(document)">{{ document.lifecycleStatus === 'ACTIVE' ? '删除文献' : '重试清理' }}</el-button>
          </div>
        </article>
      </div>
    </template>
  </main>
</template>
