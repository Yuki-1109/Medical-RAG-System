<script setup lang="ts">
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { auth } from './auth/state'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <div v-if="auth.loading" class="page-loading" role="status">正在确认本机初始化和登录状态…</div>
    <div v-show="!auth.loading"><router-view /></div>
  </el-config-provider>
</template>
