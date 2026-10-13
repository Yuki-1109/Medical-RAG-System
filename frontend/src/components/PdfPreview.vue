<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElInputNumber } from 'element-plus'
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type PDFDocumentLoadingTask, type RenderTask } from 'pdfjs-dist'
import worker from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { originalDocument } from '../api/documents'
import { previewPage } from '../document/pages'
GlobalWorkerOptions.workerSrc = worker
const props = defineProps<{ documentId: string; expectedPages: number; page?: unknown }>()
const emit = defineEmits<{ page: [value: number] }>()
const canvas = ref<HTMLCanvasElement | null>(null), host = ref<HTMLElement | null>(null)
const loading = ref(true), error = ref(''), pageError = ref(''), current = ref(1), total = ref(0), entered = ref<number | undefined>(1)
let document: PDFDocumentProxy | undefined, task: PDFDocumentLoadingTask | undefined, render: RenderTask | undefined
let controller: AbortController | undefined, generation = 0, renderGeneration = 0, requestedPage = 1
let disposal = Promise.resolve()
function dispose() {
  controller?.abort(); render?.cancel(); render = undefined
  const previous = task; task = undefined; document = undefined
  if (canvas.value) { canvas.value.width = 0; canvas.value.height = 0 }
  if (previous) disposal = disposal.then(() => previous.destroy())
}
async function draw(page: number) {
  const own = ++renderGeneration
  const previous = render; previous?.cancel(); requestedPage = page; loading.value = true; error.value = ''
  try {
    if (previous) await previous.promise.catch(cause => { if (!(cause instanceof Error && cause.name === 'RenderingCancelledException')) throw cause })
    if (!document) return
    const pdfPage = await document.getPage(page)
    if (own !== renderGeneration) return
    await nextTick(); const target = canvas.value
    if (!target) return
    const original = pdfPage.getViewport({ scale: 1 }), width = Math.max(240, (host.value?.clientWidth || 850) - 32)
    const scale = Math.min(2, width / original.width, Math.sqrt(16000000 / (original.width * original.height)))
    const viewport = pdfPage.getViewport({ scale })
    target.width = Math.ceil(viewport.width); target.height = Math.ceil(viewport.height)
    const job = pdfPage.render({ canvas: target, viewport }); render = job
    await job.promise
    if (own === renderGeneration) { current.value = page; entered.value = page; pdfPage.cleanup() }
  } catch (cause) {
    if (own === renderGeneration && !(cause instanceof Error && cause.name === 'RenderingCancelledException')) error.value = 'PDF 页面渲染失败，请重新加载原文或检查文件。'
  } finally { if (own === renderGeneration) loading.value = false }
}
async function load() {
  const own = ++generation; renderGeneration++; dispose()
  loading.value = true; error.value = ''; pageError.value = ''; total.value = 0
  controller = new AbortController(); const signal = controller.signal
  try {
    await disposal
    const original = await originalDocument(props.documentId, false, signal)
    if (own !== generation) return
    const assets = `${import.meta.env.BASE_URL}pdfjs-assets/`
    const bytes = new Uint8Array(await original.arrayBuffer())
    if (own !== generation) return
    task = getDocument({ data: bytes,
      disableAutoFetch: true, disableRange: true, disableStream: true, stopAtErrors: true,
      cMapUrl: `${assets}cmaps/`, cMapPacked: true, standardFontDataUrl: `${assets}standard_fonts/`, wasmUrl: `${assets}wasm/` })
    const loaded = await task.promise
    if (own !== generation) return
    document = loaded; total.value = loaded.numPages
    if (total.value !== props.expectedPages) throw new Error('PDF 页数与后端元数据不一致，请检查文献。')
    const requested = props.page === undefined ? 1 : previewPage(props.page, total.value)
    if (requested === null) pageError.value = `页码无效，请输入 1～${total.value} 的整数。`
    await draw(requested ?? 1)
  } catch (cause) {
    if (own === generation && !signal.aborted) error.value = cause instanceof Error && /[\u4e00-\u9fff]/.test(cause.message) ? cause.message : 'PDF 原文加载失败，请检查后端、会话和文件状态后重试。'
  } finally { if (own === generation) loading.value = false }
}
function go(value: unknown) {
  const page = previewPage(value, total.value)
  if (page === null) { pageError.value = `页码无效，请输入 1～${total.value} 的整数。`; return }
  pageError.value = ''; emit('page', page); void draw(page)
}
watch(() => props.documentId, load, { immediate: true })
watch(() => props.page, value => { if (document && previewPage(value, total.value) !== requestedPage) go(value) })
onBeforeUnmount(() => { generation++; renderGeneration++; dispose() })
</script>

<template>
  <section class="pdf-preview">
    <div class="pdf-toolbar"><el-button :disabled="loading || current <= 1 || !total" @click="go(current - 1)">上一页</el-button><span aria-live="polite">第 {{ current }} 页 / 共 {{ total }} 页</span><el-button :disabled="loading || current >= total || !total" @click="go(current + 1)">下一页</el-button>
      <el-input-number v-if="total" v-model="entered" aria-label="跳转页码" :min="1" :max="total" :precision="0" /><el-button :disabled="loading || !total" @click="go(entered)">跳转</el-button><el-button :disabled="loading" @click="load">重新加载原文</el-button></div>
    <el-alert v-if="pageError" :title="pageError" type="warning" :closable="false" show-icon />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="loading" role="status">正在加载并渲染 PDF 页面…</p>
    <div ref="host" class="pdf-canvas-host"><canvas ref="canvas" aria-label="PDF 原文页面" :data-rendered-page="!loading && !error ? current : undefined" /></div>
  </section>
</template>
