<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import * as authApi from '@/api/auth'
import { deleteAccount } from '@/api/user'
import { showMessage } from '@/utils/message'

const user = useUserStore()
const router = useRouter()

const nickname = ref(user.userInfo?.nickname ?? '')
const email = ref(user.userInfo?.email ?? '')
const oldPassword = ref('')
const newPassword = ref('')
const savingProfile = ref(false)
const changingPassword = ref(false)



async function saveProfile(): Promise<void> {
  savingProfile.value = true
  try {
    await authApi.updateProfile({ nickname: nickname.value, email: email.value })
    await user.fetchMe()
  } finally {
    savingProfile.value = false
  }
}

async function changePassword(): Promise<void> {
  if (oldPassword.value.length === 0 || newPassword.value.length < 6) return
  changingPassword.value = true
  try {
    await authApi.updatePassword(oldPassword.value, newPassword.value)
    oldPassword.value = ''
    newPassword.value = ''
  } finally {
    changingPassword.value = false
  }
}

async function onLogout(): Promise<void> {
  await user.logout()
  void router.replace('/login')
}

/* ---------------- 注销（02 v4.0 #45） ---------------- */
const showDelete = ref(false)
const deletePassword = ref('')
const deleting = ref(false)

async function onDeleteAccount(): Promise<void> {
  if (deleting.value || !deletePassword.value) return
  deleting.value = true
  try {
    await deleteAccount(deletePassword.value)
    showMessage('账号已注销', 'info')
    user.clear()
    void router.replace('/login')
  } catch {
    /* 1004 密码错误 / 400 ADMIN：拦截器已提示 */
  } finally {
    deleting.value = false
  }
}

</script>

<template>
  <div class="mx-auto max-w-chat px-4 py-6">
    <h1 class="text-display font-medium text-ink">设置</h1>
    <p class="mt-1 text-body text-ink-muted">
      {{ user.userInfo?.username }}
      <span v-if="user.isAdmin" class="ml-2 text-brand-strong">管理员</span>
    </p>

    <!-- 健康画像已迁入「个人健康科普 · 健康详情」（/health/detail，F-13 归位）；
         本页为纯账号设置（02 v4.0 §三：资料 / 改密 / 注销）。 -->

    <section class="mt-6 rounded-card border border-line bg-surface p-6">
      <h2 class="text-section font-medium text-ink">基本资料</h2>

      <div class="mt-4 space-y-4">
        <label class="block">
          <span class="block pb-1 text-body text-ink">昵称</span>
          <input
            v-model="nickname"
            type="text"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <label class="block">
          <span class="block pb-1 text-body text-ink">邮箱</span>
          <input
            v-model="email"
            type="email"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <button
          type="button"
          :disabled="savingProfile"
          class="inline-flex min-h-11 items-center justify-center rounded-btn bg-brand-strong px-4 py-2 text-body text-white disabled:opacity-40 md:min-h-0"
          @click="saveProfile"
        >
          保存
        </button>
      </div>
    </section>

    <section class="mt-4 rounded-card border border-line bg-surface p-6">
      <h2 class="text-section font-medium text-ink">修改密码</h2>

      <div class="mt-4 space-y-4">
        <label class="block">
          <span class="block pb-1 text-body text-ink">原密码</span>
          <input
            v-model="oldPassword"
            type="password"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <label class="block">
          <span class="block pb-1 text-body text-ink">新密码（6~32 位）</span>
          <input
            v-model="newPassword"
            type="password"
            class="w-full rounded-input border border-line px-3 py-2 text-body outline-none focus:border-brand-strong"
          />
        </label>

        <button
          type="button"
          :disabled="changingPassword || newPassword.length < 6"
          class="inline-flex min-h-11 items-center justify-center rounded-btn bg-brand-strong px-4 py-2 text-body text-white disabled:opacity-40 md:min-h-0"
          @click="changePassword"
        >
          修改密码
        </button>
      </div>
    </section>

    <button
      type="button"
      class="mt-6 inline-flex min-h-11 items-center justify-center rounded-btn border border-line px-4 py-2 text-body text-danger md:min-h-0"
      @click="onLogout"
    >
      退出登录
    </button>

    <button
      v-if="!showDelete"
      type="button"
      class="mt-4 text-hint text-ink-muted hover:text-danger"
      @click="showDelete = true"
    >
      注销账号
    </button>

    <!-- 注销（02 v4.0 #45）：密码确认；ADMIN 不可注销（后端 400）。
         副作用为不可逆：健康数据与个人资料库物理删。 -->
    <div v-if="showDelete" class="mt-4 rounded-card border border-danger/40 bg-surface p-4">
      <p class="text-body text-ink">
        注销后健康指标、备忘、画像、我的资料库与文档解惑将全部删除，且不可恢复。请输入密码确认。
      </p>
      <div class="mt-2 flex gap-2">
        <input
          v-model="deletePassword"
          type="password"
          placeholder="登录密码"
          class="min-w-0 flex-1 rounded-input border border-line bg-canvas px-3 py-2 text-body text-ink outline-none focus:border-danger"
        />
        <button
          type="button"
          :disabled="deleting || !deletePassword"
          class="shrink-0 rounded-btn bg-danger px-4 py-2 text-body text-white disabled:opacity-40"
          @click="onDeleteAccount"
        >
          {{ deleting ? '注销中…' : '确认注销' }}
        </button>
      </div>
    </div>
  </div>
</template>
