<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElSelect, ElOption } from 'element-plus'
import ProtectedImage from '../components/ProtectedImage.vue'
import { getImage, updateImage, validateImageMetadata, type ImageInfo, type ImageMetadata } from '../api/images'
import { formatBytes } from '../api/documents'
import { knowledgeBaseContext as context, loadCurrentKnowledgeBase } from '../knowledgebase/context'
import { confirmDeleteImage, downloadOriginal } from '../image/actions'
const route = useRoute(), router = useRouter(), image = ref<ImageInfo | null>(null), error = ref(''), success = ref(''), busy = ref(false)
const form = reactive<ImageMetadata>({ imageType: 'UNCLASSIFIED', stage: '', severity: '', keywords: '', note: '' })
let generation = 0
watch([() => route.params.id, () => route.params.imageId], async () => {
  const own = ++generation; image.value = null; error.value = ''; success.value = ''; busy.value = false
  await loadCurrentKnowledgeBase(String(route.params.id))
  if (own !== generation || !context.current) return
  try {
    const result = await getImage(String(route.params.imageId))
    if (result.knowledgeBaseId !== String(route.params.id)) throw new Error('图片不属于当前知识库，请返回列表重新选择。')
    if (own === generation) { image.value = result; Object.assign(form, { imageType: result.imageType, stage: result.stage, severity: result.severity, keywords: result.keywords, note: result.note }) }
  } catch (cause) { if (own === generation) error.value = cause instanceof Error ? cause.message : '图片加载失败。' }
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
async function act(action: 'save' | 'download' | 'delete') {
  if (!image.value || busy.value) return
  const own = generation, selected = image.value, base = String(route.params.id)
  error.value = ''; success.value = ''
  if (action === 'save') { error.value = validateImageMetadata(form); if (error.value) return }
  busy.value = true
  try {
    if (action === 'save') { const result = await updateImage(selected.id, { ...form }); if (own === generation) { image.value = result; success.value = '人工信息已保存，原图未改写。' } }
    if (action === 'download') await downloadOriginal(selected)
    if (action === 'delete' && await confirmDeleteImage(selected) && own === generation) { image.value = null; await router.replace(`/app/knowledge-bases/${base}/images`) }
  } catch (cause) {
    if (own === generation) {
      error.value = cause instanceof Error ? cause.message : '操作失败，请重试。'
      if (action === 'delete') { image.value = null; await router.replace(`/app/knowledge-bases/${base}/images`) }
    }
  } finally { if (own === generation) busy.value = false }
}
</script>
<template>
  <main class="image-detail-page"><router-link :to="`/app/knowledge-bases/${route.params.id}/images`" class="subtle-link">← 返回医学图片</router-link>
    <el-alert v-if="error || context.error" :title="error || context.error" type="error" :closable="false" show-icon />
    <template v-if="image"><div class="page-header"><div><p class="eyebrow">{{ context.current?.name }} · ID：{{ image.knowledgeBaseId }}</p><h2>{{ image.originalFilename }}</h2><p class="subtitle">{{ image.fileFormat }} · {{ image.width }} × {{ image.height }} · {{ formatBytes(image.fileSize) }}</p></div><div><el-button :disabled="busy" @click="act('download')">下载原件</el-button><el-button type="danger" plain :disabled="busy" @click="act('delete')">删除图片</el-button></div></div>
      <el-alert title="当前只展示原图。类型、分期、程度、关键词和备注均为医生人工填写，系统不验证医学真实性；疾病分类与标注尚未开发。" type="info" :closable="false" />
      <div class="image-detail-grid"><ProtectedImage :key="image.id" :image-id="image.id" :filename="image.originalFilename" />
        <el-form label-position="top" @submit.prevent="act('save')">
          <el-form-item label="人工类型"><el-select v-model="form.imageType" aria-label="人工类型"><el-option value="UNCLASSIFIED" label="未分类" /><el-option value="LESION" label="病灶" /><el-option value="NORMAL" label="正常对照" /></el-select></el-form-item>
          <el-form-item label="分期（人工填写）"><el-input v-model="form.stage" aria-label="分期" maxlength="100" show-word-limit /></el-form-item>
          <el-form-item label="严重程度（人工填写）"><el-input v-model="form.severity" aria-label="严重程度" maxlength="100" show-word-limit /></el-form-item>
          <el-form-item label="关键词"><el-input v-model="form.keywords" aria-label="关键词" maxlength="500" show-word-limit /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.note" aria-label="备注" type="textarea" :rows="4" maxlength="2000" show-word-limit /></el-form-item>
          <el-alert v-if="success" :title="success" type="success" :closable="false" /><el-button type="primary" native-type="submit" :loading="busy">保存人工信息</el-button>
        </el-form>
      </div><dl class="image-metadata"><dt>图片 ID</dt><dd>{{ image.id }}</dd><dt>SHA-256</dt><dd>{{ image.sha256 }}</dd><dt>上传时间</dt><dd>{{ new Date(image.createdAt).toLocaleString('zh-CN') }}</dd><dt>更新时间</dt><dd>{{ new Date(image.updatedAt).toLocaleString('zh-CN') }}</dd></dl>
    </template><p v-else-if="!error && !context.error" role="status">正在核验图片与知识库归属…</p>
  </main>
</template>
