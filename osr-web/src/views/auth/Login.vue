<template>
  <div class="login-container">
    <v-card class="login-card">
      <div class="login-brand">
        <img src="/icons/android-chrome-192x192.png" alt="OSR" class="login-logo" />
        <h2 class="login-title">OSR</h2>
      </div>
      <v-form ref="loginFormRef" class="login-form">
        <v-text-field
          v-model="loginForm.username"
          placeholder="用户名"
          prepend-inner-icon="mdi-account-outline"
          size="large"
          variant="outlined"
          density="comfortable"
          class="mb-2"
          :rules="[(v: any) => !!v || '请输入用户名']"
        />
        <v-text-field
          v-model="loginForm.password"
          type="password"
          placeholder="密码"
          prepend-inner-icon="mdi-lock-outline"
          size="large"
          variant="outlined"
          density="comfortable"
          class="mb-2"
          :rules="[(v: any) => !!v || '请输入密码']"
          @keyup.enter="handleLogin"
        />
        <v-btn color="primary" size="large" block :loading="loading" class="login-btn" @click="handleLogin">
          登 录
        </v-btn>
      </v-form>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from '@/composables/useMessage'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const loginFormRef = ref<any>()
const loading = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const handleLogin = async () => {
  const form = loginFormRef.value
  if (!form) return
  const { valid } = await form.validate()
  if (!valid) return
  loading.value = true
  try {
    await userStore.login(loginForm)
    message.success('登录成功')
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch {
    // 失败原因由 request 拦截器统一提示（会带上后端返回的具体原因，如「用户名或密码错误」），
    // 这里再弹一次只会叠出两条重复消息
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #e0a548 0%, #b4690e 55%, #7a4706 100%);
}

.login-card {
  width: 400px;
  padding: 32px 28px;
  border-radius: var(--osr-radius-xl);
  box-shadow: var(--osr-shadow-lg);

  .login-brand {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 12px;
    margin-bottom: 30px;
  }

  .login-logo {
    width: 56px;
    height: 56px;
    border-radius: var(--osr-radius-md);
  }

  .login-title {
    text-align: center;
    margin: 0;
    color: var(--osr-primary);
    font-size: 22px;
    font-weight: 700;
    letter-spacing: 0.5px;
  }

  .login-btn {
    width: 100%;
  }
}
</style>
