<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import * as authApi from '@/api/auth'

const router = useRouter()

const username = ref('')
const password = ref('')
const nickname = ref('')
const submitting = ref(false)

async function onSubmit(): Promise<void> {
  if (submitting.value) return
  if (!username.value.trim() || password.value.length < 6) {
    return
  }
  submitting.value = true
  try {
    await authApi.register({
      username: username.value.trim(),
      password: password.value,
      nickname: nickname.value.trim() || undefined
    })
    await router.replace('/login')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="flex h-full items-center justify-center overflow-y-auto px-4 py-6">
    <div class="w-full max-w-[360px] rounded-card border border-line bg-surface p-6 shadow-xl">
      <h1 class="grad-text text-display font-semibold">创建账号</h1>
      <p class="mt-1 text-hint text-ink-muted">用户名 4~20 位字母数字下划线，密码 6~32 位</p>

      <form class="mt-6 space-y-4" @submit.prevent="onSubmit">
        <label class="block">
          <span class="block pb-1 text-body text-ink">用户名</span>
          <input
            v-model="username"
            type="text"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <label class="block">
          <span class="block pb-1 text-body text-ink">昵称（可选）</span>
          <input
            v-model="nickname"
            type="text"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <label class="block">
          <span class="block pb-1 text-body text-ink">密码</span>
          <input
            v-model="password"
            type="password"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <button
          type="submit"
          :disabled="submitting || !username.trim() || password.length < 6"
          class="w-full rounded-btn grad-primary px-3 py-2 text-body disabled:opacity-40"
        >
          {{ submitting ? '提交中…' : '注册' }}
        </button>
      </form>

      <p class="mt-4 text-body text-ink-muted">
        已有账号？<router-link to="/login" class="text-brand-strong underline">去登录</router-link>
      </p>
    </div>
  </div>
</template>
