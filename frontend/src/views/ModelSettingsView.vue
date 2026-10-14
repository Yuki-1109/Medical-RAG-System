<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import ModelConfigForm from '../components/ModelConfigForm.vue'
import { listModels, type ModelInfo } from '../api/models'
const configs = ref<ModelInfo[]>([]), loading = ref(true), error = ref('')
async function load() {
  loading.value = true; error.value = ''
  try { configs.value = await listModels() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '读取配置失败，请重试。' }
  finally { loading.value = false }
}
function updated(config: ModelInfo) { configs.value = configs.value.map(item => item.type === config.type ? config : item) }
onMounted(load)
</script>
<template>
  <main class="model-settings-page">
    <div class="page-header"><div><p class="eyebrow">系统设置</p><h2>模型 API 安全配置</h2></div></div>
    <el-alert title="未来 RAG 调用会将检索文本片段发送到你配置的外部 API。未经授权或未去标识化的患者资料不应发送到第三方服务。" type="warning" :closable="false" show-icon />
    <p class="form-description">聊天与 Embedding 可使用不同服务、模型和密钥。远程接口使用 HTTPS；本机 HTTP 只适用于明确启用并信任的本机服务。首次保存密钥会生成独立主密钥文件，请按 B1 验收文档私下备份，丢失后无法解密原配置。</p>
    <p v-if="loading" role="status">正在读取模型配置…</p>
    <template v-else-if="error"><el-alert :title="error" type="error" :closable="false" show-icon /><el-button @click="load">重新读取</el-button></template>
    <div v-else class="model-config-grid"><ModelConfigForm v-for="config in configs" :key="config.type" :config="config" @updated="updated" /></div>
  </main>
</template>
