<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElTag } from 'element-plus'
import { fetchHealth, type HealthResponse, type HealthStatus } from '../api/health'

const health = ref<HealthResponse | null>(null)
const loading = ref(false)
const error = ref('')
const labels: Record<HealthStatus, string> = { UP: '正常', DOWN: '异常', UNKNOWN: '未检查' }
const components = computed(() => health.value ? [
  { name: 'Spring Boot 后端', ...health.value.backend },
  { name: 'PostgreSQL 数据库', ...health.value.database },
  { name: 'pgvector 扩展', ...health.value.pgvector },
  { name: 'Flyway 数据库迁移', ...health.value.migrations },
] : [])

async function check() {
  loading.value = true
  health.value = null
  error.value = ''
  try {
    health.value = await fetchHealth()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '检查失败，请重试。'
  } finally {
    loading.value = false
  }
}

onMounted(check)
</script>

<template>
  <main class="startup-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">医疗文献与医学图片知识库</p>
        <h1>医疗 RAG 系统 · 启动检查</h1>
        <p class="subtitle">A1 工程与数据库：检查本机服务是否实际可用。</p>
      </div>
      <el-tag effect="plain">开发阶段 A1</el-tag>
    </header>

    <el-card shadow="never" class="status-card">
      <template #header>
        <div class="card-header">
          <h2>服务状态</h2>
          <el-button type="primary" :loading="loading" @click="check">重新检查</el-button>
        </div>
      </template>
      <p v-if="loading" role="status">正在请求后端并检查数据库连接…</p>
      <el-alert v-else-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-else-if="health">
        <el-alert
          :title="health.status === 'UP' ? '工程启动成功：后端、数据库、扩展及迁移均正常。' : '检查发现异常，请根据下方状态排查。'"
          :type="health.status === 'UP' ? 'success' : 'error'"
          :closable="false"
          show-icon
        />
        <ul class="component-list">
          <li v-for="component in components" :key="component.name" class="component-row">
            <div>
              <h3>{{ component.name }}</h3>
              <p>{{ component.message }}</p>
              <span v-if="component.version" class="version">版本 {{ component.version }}</span>
            </div>
            <el-tag :type="component.status === 'UP' ? 'success' : component.status === 'DOWN' ? 'danger' : 'info'">
              {{ labels[component.status] }}
            </el-tag>
          </li>
        </ul>
        <p class="check-time">后端检查时间：{{ new Date(health.checkedAt).toLocaleString('zh-CN') }}</p>
      </template>
    </el-card>

    <el-alert
      title="当前仅完成工程基础。管理员登录、知识库、文件上传及 RAG 问答将在后续任务逐步开发。"
      type="info"
      :closable="false"
    />
    <footer>资料管理与辅助查询工具 · 按需求文档分阶段开发</footer>
  </main>
</template>
