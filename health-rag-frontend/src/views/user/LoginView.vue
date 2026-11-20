<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import PulseMark from '@/components/PulseMark.vue'

const user = useUserStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const submitting = ref(false)

async function onSubmit(): Promise<void> {
  if (submitting.value) return
  if (!username.value.trim() || !password.value) {
    return
  }
  submitting.value = true
  try {
    await user.login(username.value.trim(), password.value)
    // 守卫记录了回跳地址；无则回对话页
    const redirect = (route.query.redirect as string | undefined) || '/chat'
    await router.replace(redirect)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="flex h-full items-center justify-center overflow-y-auto px-4 py-6">
    <div class="w-full max-w-[360px] rounded-card border border-line bg-surface p-6 shadow-xl">
      <div class="flex items-center gap-3">
        <PulseMark :width="64" />
        <div>
          <h1 class="text-display font-semibold text-ink">登录</h1>
          <p class="text-hint text-ink-muted">健康科普 RAG 系统</p>
        </div>
      </div>

      <form class="mt-6 space-y-4" @submit.prevent="onSubmit">
        <label class="block">
          <span class="block pb-1 text-body text-ink">用户名</span>
          <input
            v-model="username"
            type="text"
            autocomplete="username"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <label class="block">
          <span class="block pb-1 text-body text-ink">密码</span>
          <input
            v-model="password"
            type="password"
            autocomplete="current-password"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <button
          type="submit"
          :disabled="submitting || !username.trim() || !password"
          class="w-full rounded-btn grad-primary px-3 py-2 text-body disabled:opacity-40"
        >
          {{ submitting ? '登录中…' : '登录' }}
        </button>
      </form>

      <p class="mt-4 text-body text-ink-muted">
        还没有账号？
        <router-link to="/register" class="text-brand-strong underline">去注册</router-link>
      </p>
    </div>
  </div>
</template>
