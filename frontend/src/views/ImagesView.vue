<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElEmpty, ElPagination, ElProgress, ElTag } from 'element-plus'
import { listImages, uploadImage, imageLimits, validateImageFile, imageTypeLabel, type ImageInfo } from '../api/images'
import { knowledgeBaseContext as context, loadCurrentKnowledgeBase } from '../knowledgebase/context'
import { formatBytes } from '../api/documents'
import ProtectedImage from '../components/ProtectedImage.vue'
import { confirmDeleteImage, downloadOriginal } from '../image/actions'
const route = useRoute(), router = useRouter()
const rows = ref<ImageInfo[]>([]), loading = ref(false), error = ref(''), uploadBusy = ref(false), progress = ref(0)
const maxBytes = ref<number | null>(null), selected = ref<File | null>(null), input = ref<HTMLInputElement | null>(null)
const deleting = ref(''), downloading = ref(''), dimensionNote = ref(''), page = ref(1)
const visibleRows = computed(() => rows.value.slice((page.value - 1) * 12, page.value * 12))
let generation = 0, upload: AbortController | undefined
async function load() {
  const own = ++generation, base = String(route.params.id)
  rows.value = []; page.value = 1; error.value = ''; loading.value = true
  await loadCurrentKnowledgeBase(base)
  if (own !== generation || !context.current) { if (own === generation) loading.value = false; return }
  try {
    const [values, limit] = await Promise.all([listImages(base), imageLimits()])
    if (own === generation) { rows.value = values; maxBytes.value = limit.maxImageBytes; dimensionNote.value = `${limit.maxWidth} × ${limit.maxHeight}，总像素不超过 ${limit.maxPixels.toLocaleString()}` }
  } catch (cause) { if (own === generation) error.value = cause instanceof Error ? cause.message : '图片加载失败，请重试。' }
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
  if (selected.value && maxBytes.value) error.value = validateImageFile(selected.value, maxBytes.value)
}
async function send() {
  if (!selected.value || !context.current || !maxBytes.value || uploadBusy.value) return
  error.value = validateImageFile(selected.value, maxBytes.value); if (error.value) return
  const base = context.current.id, own = generation
  upload = new AbortController(); const controller = upload
  uploadBusy.value = true; progress.value = 0
  try {
    await uploadImage(base, selected.value, value => { if (own === generation) progress.value = value }, controller.signal)
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
async function remove(image: ImageInfo) {
  if (deleting.value) return
  const base = String(route.params.id), own = generation
  deleting.value = image.id; error.value = ''
  try { if (await confirmDeleteImage(image) && String(route.params.id) === base) await load() }
  catch (cause) {
    if (own === generation) { const message = cause instanceof Error ? cause.message : '清理未完成，请重试。'; await load(); error.value = message }
  } finally { deleting.value = '' }
}
async function download(image: ImageInfo) {
  if (downloading.value) return
  downloading.value = image.id; error.value = ''
  try { await downloadOriginal(image) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '下载失败，请重试。' }
  finally { downloading.value = '' }
}
</script>

<template>
  <main class="images-page">
    <router-link :to="`/app/knowledge-bases/${route.params.id}`" class="subtle-link">← 返回知识库详情</router-link>
    <div class="page-header"><div><p class="eyebrow">当前知识库 · ID：{{ route.params.id }}</p><h2>图片管理</h2><p v-if="context.current" class="subtitle">{{ context.current.name }} · 图片 {{ context.current.imageCount }}</p></div><el-button :disabled="uploadBusy" :loading="loading" @click="load">刷新图片</el-button></div>
    <el-alert v-if="context.error" :title="context.error" type="error" :closable="false" show-icon />
    <template v-if="context.current">
      <el-alert v-if="context.current.deletionPending" title="知识库删除未完成，资料已停止访问。请返回知识库详情重试删除。" type="warning" :closable="false" show-icon />
      <section class="image-upload" aria-label="图片上传">
        <label for="image-file">选择 JPG/PNG 图片</label><input id="image-file" ref="input" type="file" accept=".jpg,.jpeg,.png,image/jpeg,image/png" :disabled="uploadBusy || !maxBytes || context.current.deletionPending" @change="choose" />
        <el-button type="primary" :loading="uploadBusy" :disabled="!selected || !!error || !maxBytes || context.current.deletionPending" @click="send">开始上传</el-button>
        <p v-if="maxBytes" class="library-stage-note">上限 {{ formatBytes(maxBytes) }}。{{ dimensionNote }}。原件保持不变；标签由医生人工填写，系统不验证医学真实性。</p>
        <div v-if="uploadBusy" role="status"><el-progress :percentage="progress" /><span>{{ progress === 100 ? '传输完成，正在校验并保存…' : `正在上传 ${progress}%…` }}</span></div>
      </section>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-alert" />
      <p v-if="loading" role="status">正在加载真实图片记录…</p>
      <el-empty v-else-if="!error && !rows.length" description="当前知识库还没有图片，请上传 JPG/PNG 原件。" />
      <div v-else class="image-list">
        <article v-for="image in visibleRows" :key="image.id" class="image-row" :data-image-id="image.id">
          <ProtectedImage v-if="image.lifecycleStatus === 'ACTIVE' && !context.current.deletionPending" :key="image.id" :image-id="image.id" :filename="image.originalFilename" />
          <div class="image-row-info"><h3>{{ image.originalFilename }}</h3><p>{{ formatBytes(image.fileSize) }} · {{ image.width }} × {{ image.height }} · {{ image.fileFormat }} · {{ new Date(image.createdAt).toLocaleString('zh-CN') }}</p>
            <el-tag v-if="image.lifecycleStatus === 'ACTIVE'">{{ imageTypeLabel(image.imageType) }}</el-tag>
            <el-tag v-else type="warning">{{ image.lifecycleStatus === 'UPLOADING' ? '上传未完成，可清理' : image.lifecycleStatus === 'DELETING' ? '待清理，可重试' : '清理失败，可重试' }}</el-tag>
            <p v-if="image.cleanupError" class="image-warning">{{ image.cleanupError }}</p>
          </div>
          <div class="image-row-actions">
            <el-button v-if="image.lifecycleStatus === 'ACTIVE' && !context.current.deletionPending" @click="router.push(`/app/knowledge-bases/${route.params.id}/images/${image.id}`)">查看原图及信息</el-button>
            <el-button v-if="image.lifecycleStatus === 'ACTIVE' && !context.current.deletionPending" :loading="downloading === image.id" @click="download(image)">下载原件</el-button>
            <el-button type="danger" plain :disabled="uploadBusy || (!!deleting && deleting !== image.id)" :loading="deleting === image.id" @click="remove(image)">{{ image.lifecycleStatus === 'ACTIVE' ? '删除图片' : '重试清理' }}</el-button>
          </div>
        </article>
      </div>
      <el-pagination v-if="rows.length > 12" v-model:current-page="page" :page-size="12" :total="rows.length" layout="prev, pager, next" aria-label="图片分页" />
    </template>
  </main>
</template>
