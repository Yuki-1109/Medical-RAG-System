<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton } from 'element-plus'
import { auth } from '../auth/state'
import { safeDestination } from '../auth/redirect'
const router = useRouter()
const route = useRoute()
</script>

<template>
  <main class="connection-page">
    <div class="brand-mark" aria-hidden="true">M</div>
    <h1>暂时无法连接本机服务</h1>
    <p>请确认后端和数据库已启动，再重新检查。连接恢复后会重新核验已有账号与登录状态。</p>
    <el-alert :title="auth.connectionError || '正在检查服务状态…'" type="error" :closable="false" show-icon />
    <el-button type="primary" :loading="auth.loading" @click="router.replace(safeDestination(route.query.redirect))">重新连接</el-button>
    <router-link to="/health" class="subtle-link">打开服务检查</router-link>
  </main>
</template>
