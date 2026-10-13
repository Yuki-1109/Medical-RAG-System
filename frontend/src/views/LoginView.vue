<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElInput } from 'element-plus'
import AuthFrame from '../components/AuthFrame.vue'
import { auth, login } from '../auth/state'
import { safeDestination } from '../auth/redirect'

const route = useRoute()
const router = useRouter()
const username = ref(auth.suggestedUsername)
const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  if (busy.value) return
  error.value = ''
  if (!username.value || !password.value) { error.value = '请输入用户名和密码。'; return }
  busy.value = true
  try {
    await login(username.value, password.value)
    await router.replace(safeDestination(route.query.redirect))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请重试。'
  } finally { password.value = ''; busy.value = false }
}
</script>

<template>
  <AuthFrame>
    <p class="eyebrow">本地管理员</p>
    <h2>登录研究资料工作台</h2>
    <p class="form-description">使用本机已有管理员账号登录，继续使用你的研究环境。</p>
    <el-alert v-if="auth.notice" :title="auth.notice" type="info" :closable="false" show-icon class="form-alert" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-alert" />
    <form @submit.prevent="submit" class="auth-form">
      <label for="login-username">用户名</label>
      <el-input id="login-username" v-model="username" autocomplete="username" maxlength="64" :disabled="busy" placeholder="请输入已有用户名" />
      <label for="login-password">密码</label>
      <el-input id="login-password" v-model="password" type="password" autocomplete="current-password" :disabled="busy" show-password placeholder="请输入密码" />
      <el-button native-type="submit" type="primary" :loading="busy" class="submit-button">登录</el-button>
    </form>
    <p class="form-footnote">已有管理员无需重新初始化。登录状态由本机服务验证。</p>
    <router-link to="/health" class="subtle-link">检查本机服务状态</router-link>
  </AuthFrame>
</template>
