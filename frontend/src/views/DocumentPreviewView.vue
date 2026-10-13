<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton } from 'element-plus'
import PdfPreview from '../components/PdfPreview.vue'
import { getDocument, type DocumentInfo } from '../api/documents'
import { loadCurrentKnowledgeBase, knowledgeBaseContext as context } from '../knowledgebase/context'
import { downloadOriginal } from '../document/actions'
const route = useRoute(), router = useRouter(), document = ref<DocumentInfo | null>(null), error = ref(''), busy = ref(false)
let generation = 0
watch([() => route.params.id, () => route.params.documentId], async () => {
  const own = ++generation; document.value = null; error.value = ''
  await loadCurrentKnowledgeBase(String(route.params.id))
  if (own !== generation || !context.current) return
  try {
    const value = await getDocument(String(route.params.documentId))
    if (value.knowledgeBaseId !== String(route.params.id)) throw new Error('文献不属于当前知识库，请返回列表重新选择。')
    if (own === generation) document.value = value
  } catch (cause) { if (own === generation) error.value = cause instanceof Error ? cause.message : '文献加载失败，请重试。' }
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
async function download() {
  if (!document.value || busy.value) return
  busy.value = true
  try { await downloadOriginal(document.value) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '下载失败，请重试。' }
  finally { busy.value = false }
}
</script>
<template>
  <main class="document-preview-page">
    <router-link :to="`/app/knowledge-bases/${route.params.id}/documents`" class="subtle-link">← 返回文献管理</router-link>
    <el-alert v-if="error || context.error" :title="error || context.error" type="error" :closable="false" show-icon />
    <template v-if="document"><div class="page-header"><div><p class="eyebrow">{{ context.current?.name }} · 文献原件</p><h2>{{ document.originalFilename }}</h2></div><el-button :loading="busy" @click="download">下载原件</el-button></div>
      <PdfPreview :key="document.id" :document-id="document.id" :expected-pages="document.pageCount!" :page="route.query.page" @page="value => router.replace({ query: { page: String(value) } })" />
    </template>
    <p v-else-if="!error && !context.error" role="status">正在核验文献与知识库归属…</p>
  </main>
</template>
