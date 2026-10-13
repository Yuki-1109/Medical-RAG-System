<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElInput } from 'element-plus'
import AuthFrame from '../components/AuthFrame.vue'
import { auth, setupAdministrator } from '../auth/state'
import { ApiError } from '../api/client'

const router = useRouter()
const username = ref('')
const password = ref('')
const confirmation = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  if (busy.value) return
  error.value = ''
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/.test(username.value)) {
    error.value = '用户名需为 3～64 位字母、数字、点、下划线或连字符，并以字母或数字开头。'
    return
  }
  if (password.value.length < 12 || new TextEncoder().encode(password.value).length > 72) {
    error.value = '密码至少需要 12 个字符，UTF-8 长度不得超过 72 字节。'
    return
  }
  if (password.value !== confirmation.value) { error.value = '两次输入的密码不一致。'; return }
  busy.value = true
  try {
    await setupAdministrator(username.value, password.value)
    await router.replace({ name: 'login' })
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 'SETUP_ALREADY_COMPLETED') {
      auth.initialized = true
      auth.notice = '管理员已创建，请使用现有账号登录。'
      await router.replace({ name: 'login' })
    } else { error.value = cause instanceof Error ? cause.message : '创建失败，请重试。' }
  } finally { password.value = ''; confirmation.value = ''; busy.value = false }
}
</script>

<template>
  <AuthFrame>
    <p class="eyebrow">首次使用</p>
    <h2>创建本地管理员</h2>
    <p class="form-description">仅在首次安装时创建一个管理员。创建完成后，请使用该账号登录。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-alert" />
    <form @submit.prevent="submit" class="auth-form">
      <label for="setup-username">管理员用户名</label>
      <el-input id="setup-username" v-model="username" autocomplete="username" maxlength="64" :disabled="busy" placeholder="请输入用户名" />
      <p class="field-help">3～64 位，可使用字母、数字、点、下划线或连字符。</p>
      <label for="setup-password">管理员密码</label>
      <el-input id="setup-password" v-model="password" type="password" autocomplete="new-password" :disabled="busy" show-password placeholder="至少 12 个字符" />
      <label for="setup-confirmation">确认密码</label>
      <el-input id="setup-confirmation" v-model="confirmation" type="password" autocomplete="new-password" :disabled="busy" show-password placeholder="再次输入密码" />
      <el-button native-type="submit" type="primary" :loading="busy" class="submit-button">创建管理员</el-button>
    </form>
    <p class="form-footnote">这是本机的一次性初始化，不提供公开注册。请妥善保管账号和密码。</p>
    <router-link to="/health" class="subtle-link">检查本机服务状态</router-link>
  </AuthFrame>
</template>
