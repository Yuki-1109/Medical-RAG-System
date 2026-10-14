<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElCheckbox, ElInput, ElOption, ElSelect, ElTag } from 'element-plus'
import { listModels, modelStatus, saveModel, testModel, type ModelInfo } from '../api/models'
const props = defineProps<{ config: ModelInfo }>()
const emit = defineEmits<{ updated: [config: ModelInfo] }>()
const draft = reactive({ protocol: 'OPENAI_COMPATIBLE', baseUrl: '', modelId: '', apiKey: '', deleteKey: false })
const busy = ref(false), error = ref(''), notice = ref('')
const title = computed(() => props.config.type === 'CHAT' ? '聊天模型配置' : 'Embedding 模型配置')
watch(() => props.config, config => {
  draft.protocol = config.protocol; draft.baseUrl = config.baseUrl; draft.modelId = config.modelId
  draft.apiKey = ''; draft.deleteKey = false
}, { immediate: true })
const dirty = computed(() => draft.protocol !== props.config.protocol || draft.baseUrl !== props.config.baseUrl || draft.modelId !== props.config.modelId || !!draft.apiKey || draft.deleteKey)
async function save() {
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const result = await saveModel({ type: props.config.type, ...draft })
    emit('updated', result); draft.apiKey = ''; draft.deleteKey = false
    notice.value = '配置已保存；保存不会调用模型，请单独测试连接。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败，请重试。' }
  finally { busy.value = false }
}
async function test() {
  busy.value = true; error.value = ''; notice.value = ''
  try { emit('updated', await testModel(props.config.type)) }
  catch (cause) {
    error.value = cause instanceof Error ? cause.message : '测试失败，请重试。'
    // Fetch persisted test state without losing a draft if the read itself fails.
    try { const current = (await listModels()).find(item => item.type === props.config.type); if (current) emit('updated', current) } catch { }
  } finally { busy.value = false }
}
</script>
<template>
  <el-card shadow="never" class="model-config-card" :data-model-type="config.type">
    <template #header><div class="card-header"><h2>{{ title }}</h2><el-tag :type="config.testStatus === 'FAILED' ? 'danger' : config.testStatus === 'SUCCESS' && !dirty ? 'success' : 'info'">{{ dirty ? '有未保存修改，需保存并重新测试' : modelStatus(config) }}</el-tag></div></template>
    <p class="field-help">当前服务：{{ config.baseUrl || '未配置' }}<br />当前模型：{{ config.modelId || '未配置' }}</p>
    <form class="model-config-form" @submit.prevent="save">
      <label :for="`${config.type}-protocol`">接口协议</label>
      <el-select :id="`${config.type}-protocol`" v-model="draft.protocol" :disabled="busy" aria-label="接口协议"><el-option label="OpenAI-compatible" value="OPENAI_COMPATIBLE" /></el-select>
      <label :for="`${config.type}-url`">API Base URL</label>
      <el-input :id="`${config.type}-url`" v-model="draft.baseUrl" :disabled="busy" maxlength="2048" placeholder="填写服务文档中的 API 根路径，例如 https://服务域名/v1" />
      <label :for="`${config.type}-model`">Model ID</label>
      <el-input :id="`${config.type}-model`" v-model="draft.modelId" :disabled="busy" maxlength="200" autocomplete="off" />
      <label :for="`${config.type}-key`">API Key</label>
      <el-input :id="`${config.type}-key`" v-model="draft.apiKey" type="password" :disabled="busy || draft.deleteKey" maxlength="4096" autocomplete="new-password" placeholder="留空保留现有密钥" />
      <p class="field-help">{{ config.keyConfigured ? '密钥已保存；不会回填明文。留空表示保留现有密钥。' : '尚未保存密钥，请仅在此网页输入，勿发送到聊天记录。' }}</p>
      <el-checkbox v-model="draft.deleteKey" :disabled="busy" @change="draft.apiKey = ''">删除当前类型的已保存密钥（保存后生效）</el-checkbox>
      <p v-if="config.type === 'EMBEDDING'" class="field-help">更换 Embedding 服务、模型或维度后，未来已建立的向量索引可能需要重建。当前阶段尚未建立向量索引。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
      <p v-if="!dirty && config.testStatus === 'FAILED'" class="document-warning">{{ config.testMessage }}</p>
      <p v-if="!dirty && config.testedAt" class="field-help">最近测试：{{ new Date(config.testedAt).toLocaleString('zh-CN') }}<span v-if="config.embeddingDimension"> · 向量维度 {{ config.embeddingDimension }}</span></p>
      <div class="model-actions"><el-button native-type="submit" type="primary" :loading="busy">保存配置</el-button><el-button :disabled="busy || dirty || !config.configured" @click="test">测试连接</el-button></div>
      <p class="field-help">测试只使用无医学内容的固定短句，可能消耗少量 API 额度；请确认配置已保存后点击。</p>
    </form>
  </el-card>
</template>
