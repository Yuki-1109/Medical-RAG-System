<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { originalImage } from '../api/images'
import { auth } from '../auth/state'
const props = defineProps<{ imageId: string; filename: string }>()
const url = ref(''), error = ref(''), loading = ref(false)
let generation = 0, request: AbortController | undefined
function release() { request?.abort(); if (url.value) URL.revokeObjectURL(url.value); url.value = '' }
watch([() => props.imageId, () => auth.user?.id], async () => {
  const own = ++generation; release(); error.value = ''; loading.value = false
  if (!auth.user) return
  request = new AbortController(); const controller = request; loading.value = true
  try {
    const original = await originalImage(props.imageId, false, controller.signal)
    if (own === generation && !controller.signal.aborted) url.value = URL.createObjectURL(original)
  } catch (cause) { if (own === generation && !controller.signal.aborted) error.value = cause instanceof Error ? cause.message : '原图加载失败。' }
  finally { if (own === generation) loading.value = false }
}, { immediate: true })
onBeforeUnmount(() => { generation++; release() })
</script>
<template><div class="protected-image"><p v-if="loading" role="status">正在加载原图…</p><p v-else-if="error" role="alert">{{ error }}</p><img v-else-if="url" :src="url" :alt="filename" @error="error = '原图无法显示，请检查文件或浏览器。'" /></div></template>
