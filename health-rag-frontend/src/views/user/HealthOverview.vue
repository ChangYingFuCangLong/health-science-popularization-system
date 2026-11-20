<script setup lang="ts">
/* ============================================================
 * 个人健康科普 · 概览（/health，11 号 §〇 三模块 v3）
 *
 * 本页是个人解惑的唯一家：列表（#58 多条）+ 新建（title 可选，
 * 健康信息全空 → 2008 引导先填写）+ 行内删除（两步确认）+ 进入对话。
 * 摘要卡（画像/指标/资料库）只做概览与跳转，管理功能在二级页。
 * ============================================================ */

import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as chatApi from '@/api/chat'
import * as healthApi from '@/api/health'
import * as kbApi from '@/api/userKb'
import type { ChatSession } from '@/api/types'
import { showMessage } from '@/utils/message'

const router = useRouter()

const sessions = ref<ChatSession[]>([])
const loading = ref(false)
const creating = ref(false)
const newTitle = ref('')
const showCreate = ref(false)
/** 两步确认删除的会话 id（3 秒内再点生效） */
const confirmId = ref<number | null>(null)

/** 摘要：资料库配额 + 备忘条数 */
const kbStats = ref<kbApi.PersonalKbStats | null>(null)
const kvCount = ref<number | null>(null)

async function loadSessions(): Promise<void> {
  loading.value = true
  try {
    sessions.value = await chatApi.listHealthSessions({ silent: true })
  } catch {
    sessions.value = []
  } finally {
    loading.value = false
  }
}

async function loadSummary(): Promise<void> {
  try {
    kbStats.value = await kbApi.getStats({ silent: true })
  } catch {
    kbStats.value = null
  }
  try {
    const kv = await healthApi.listHealthKv({ silent: true })
    kvCount.value = kv.length
  } catch {
    kvCount.value = null
  }
}

async function createSession(): Promise<void> {
  if (creating.value) return
  creating.value = true
  try {
    const created = await chatApi.createHealthSession(newTitle.value.trim() || undefined)
    showCreate.value = false
    newTitle.value = ''
    showMessage('已创建', 'success')
    void router.push(`/health/chat/${created.id}`)
  } catch {
    /* 2008 健康信息未填写：拦截器文案引导先填写 */
  } finally {
    creating.value = false
  }
}

async function removeSession(s: ChatSession): Promise<void> {
  if (confirmId.value !== s.id) {
    confirmId.value = s.id
    window.setTimeout(() => {
      if (confirmId.value === s.id) confirmId.value = null
    }, 3000)
    return
  }
  confirmId.value = null
  try {
    await chatApi.deleteSession(s.id) // health 会话适用通用删除（02 §1.5 矩阵）
    await loadSessions()
  } catch {
    /* 拦截器已提示 */
  }
}

onMounted(() => {
  void loadSessions()
  void loadSummary()
})
</script>

<template>
  <div class="mx-auto max-w-list px-4 py-6">
    <div class="flex items-center justify-between gap-3">
      <div>
        <h1 class="text-display font-semibold text-ink">个人健康科普</h1>
        <p class="mt-1 text-body text-ink-muted">
          个人解惑结合你的画像、指标、备忘与资料库回答；通用解惑不携带这些信息。
        </p>
      </div>
      <router-link
        to="/health/detail"
        class="shrink-0 rounded-btn border border-line px-3 py-1.5 text-body text-ink-muted hover:border-brand hover:text-brand"
      >
        健康详情
      </router-link>
    </div>

    <!-- 摘要卡 -->
    <div class="mt-4 grid grid-cols-2 gap-3 lg:grid-cols-3">
      <router-link to="/health/detail" class="card-lift rounded-card border border-line bg-surface p-4">
        <p class="text-hint text-ink-muted">健康画像与指标</p>
        <p class="mt-1 text-body text-ink">画像 / 目录化指标 / 健康备忘 / 报告识读</p>
        <p class="mt-1 text-hint text-brand">去健康详情 →</p>
      </router-link>
      <router-link to="/health/kb" class="card-lift rounded-card border border-line bg-surface p-4">
        <p class="text-hint text-ink-muted">我的资料库</p>
        <p class="tnum mt-1 text-xl font-semibold text-ink">
          {{ kbStats?.documentCount ?? '—' }}
          <span class="text-body text-ink-muted">份资料</span>
        </p>
        <p class="mt-1 text-hint text-brand">去管理 →</p>
      </router-link>
      <div class="rounded-card border border-line bg-surface p-4">
        <p class="text-hint text-ink-muted">健康备忘</p>
        <p class="tnum mt-1 text-xl font-semibold text-ink">
          {{ kvCount ?? '—' }}
          <span class="text-body text-ink-muted">条</span>
        </p>
        <p class="mt-1 text-hint text-ink-muted/80">信息型记录，注入个人解惑上下文</p>
      </div>
    </div>

    <!-- 个人解惑区（本页核心） -->
    <section class="mt-6 rounded-card border border-line bg-surface p-4">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 class="text-section font-medium text-ink">个人解惑</h2>
          <p class="text-hint text-ink-muted">可创建多条；回答结合你的全部个人健康信息</p>
        </div>
        <button
          type="button"
          class="rounded-btn grad-primary px-4 py-2 text-body"
          @click="showCreate = !showCreate"
        >
          新建个人解惑
        </button>
      </div>

      <form v-if="showCreate" class="mt-3 flex gap-2" @submit.prevent="createSession">
        <input
          v-model="newTitle"
          type="text"
          placeholder="标题（可选，如「睡眠改善计划」）"
          class="min-w-0 flex-1 rounded-input border border-line bg-canvas px-3 py-2 text-body text-ink outline-none placeholder:text-ink-muted focus:border-brand"
        />
        <button
          type="submit"
          :disabled="creating"
          class="shrink-0 rounded-btn border border-line bg-surface-2 px-4 py-2 text-body text-ink disabled:opacity-40"
        >
          {{ creating ? '创建中…' : '创建' }}
        </button>
      </form>

      <p v-if="loading" class="mt-4 text-body text-ink-muted">加载中…</p>
      <p v-else-if="sessions.length === 0" class="mt-4 text-body text-ink-muted">
        还没有个人解惑。创建一条，让回答基于你的健康信息与资料。
      </p>

      <ul v-else class="mt-3 divide-y divide-line">
        <li v-for="s in sessions" :key="s.id" class="flex items-center gap-3 py-3">
          <button
            type="button"
            class="min-w-0 flex-1 text-left"
            @click="void router.push(`/health/chat/${s.id}`)"
          >
            <span class="block truncate text-body font-medium text-ink">{{ s.title }}</span>
            <span class="tnum mt-0.5 block text-hint text-ink-muted">
              {{ s.messageCount }} 条消息 · 更新于 {{ s.updateTime.slice(5, 16) }}
            </span>
          </button>
          <button
            type="button"
            class="shrink-0 rounded-btn px-2 py-1 text-hint transition-colors"
            :class="confirmId === s.id ? 'font-medium text-danger' : 'text-ink-muted hover:text-danger'"
            @click="removeSession(s)"
          >
            {{ confirmId === s.id ? '确认删除' : '删除' }}
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>
