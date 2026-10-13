<script setup lang="ts">
import { ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElTag } from 'element-plus'
import { auth, verifySession } from '../auth/state'
const busy = ref(false)
const error = ref('')
async function check() {
  busy.value = true
  error.value = ''
  try { await verifySession() }
  catch (cause) { if (auth.user) error.value = cause instanceof Error ? cause.message : '检查失败，请重试。' }
  finally { busy.value = false }
}
</script>

<template>
  <div class="home-page">
    <section class="welcome-panel"><p class="eyebrow">本地研究环境</p><h2>欢迎，{{ auth.user?.username }}</h2><p>账号已通过本机服务验证。你可以检查服务状态，或安全退出登录。</p></section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div class="home-grid">
      <el-card shadow="never"><h3>当前管理员</h3><dl class="profile-fields"><dt>用户名</dt><dd>{{ auth.user?.username }}</dd><dt>账号创建时间</dt><dd>{{ auth.user ? new Date(auth.user.createdAt).toLocaleString('zh-CN') : '' }}</dd><dt>登录状态</dt><dd><el-tag type="success">已登录</el-tag></dd></dl><el-button :loading="busy" @click="check">核验登录状态</el-button></el-card>
      <el-card shadow="never"><h3>本机服务检查</h3><p class="card-description">查看后端、数据库、pgvector 与数据库迁移的实际运行状态。</p><router-link to="/app/health" class="primary-link">查看服务状态 →</router-link></el-card>
    </div>
    <router-link to="/app/knowledge-bases" class="primary-link">进入我的知识库 →</router-link>
    <el-alert title="当前已提供登录、知识库管理、PDF 与 JPG/PNG 上传、原件预览/下载/删除、图片人工信息及服务检查。文字处理、索引、疾病分类、图层和问答仍待开发。" type="info" :closable="false" />
  </div>
</template>
