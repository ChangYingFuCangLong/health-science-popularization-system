<script setup lang="ts">
/* ============================================================
 * 个人解惑对话（个人健康科普 · /health/chat/:sessionId，11 号 §〇）
 *
 * 复用对话组件（MessageItem/MessageInput）与 chat store——
 * 流式协议、对照解答、矛盾提醒全部继承；本页特有的只有：
 *   - 无侧栏（个人解惑的列表/创建在概览页，入口唯一）
 *   - 健康注入横幅：done.healthUsed 以服务端为准 + 「完善个人健康信息」入口
 * ============================================================ */

import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore, type LocalMessage } from '@/stores/chat'
import { usePulse } from '@/composer/usePulse'
import { useAutoRead } from '@/composer/useAutoRead'
import { useSpeech } from '@/composer/useSpeech'
import type { FeedbackType } from '@/api/chat'
import type { ChatSession } from '@/api/types'
import * as chatApi from '@/api/chat'
import { createHealthMetric, createHealthKv } from '@/api/health'
import MessageItem from '@/components/MessageItem.vue'
import MessageInput from '@/components/MessageInput.vue'
import MessageSkeleton from '@/components/MessageSkeleton.vue'
import DisclaimerBar from '@/components/DisclaimerBar.vue'
import { showMessage } from '@/utils/message'

const route = useRoute()
const chat = useChatStore()
const pulse = usePulse()
const autoRead = useAutoRead()
const { speak } = useSpeech()

const scroller = ref<HTMLElement | null>(null)
const input = ref<InstanceType<typeof MessageInput> | null>(null)
const loading = ref(false)

const stickToBottom = ref(true)

const sessionId = computed<number | null>(() => {
  const id = Number(route.params.sessionId)
  return Number.isFinite(id) && id > 0 ? id : null
})

/** 会话列表恒 normal（02 §1.5），health 会话标题须从 #58 专用接口解析 */
const healthSession = ref<ChatSession | null>(null)
const currentTitle = computed(
  () => healthSession.value?.title ?? '个人解惑'
)

watch(
  sessionId,
  async (id) => {
    if (id == null) return
    try {
      const list = await chatApi.listHealthSessions({ silent: true })
      healthSession.value = list.find((s) => s.id === id) ?? null
    } catch {
      healthSession.value = null
    }
  },
  { immediate: true }
)

watch(
  sessionId,
  async (id) => {
    loading.value = true
    try {
      await chat.loadSession(id)
    } finally {
      loading.value = false
    }
    await nextTick()
    scrollToBottom(true)
  },
  { immediate: true }
)

onMounted(() => {
  void chat.loadSessions()
  const prefill = sessionStorage.getItem('health-rag-prefill')
  if (prefill) {
    sessionStorage.removeItem('health-rag-prefill')
    void nextTick(() => input.value?.setDraft(prefill))
  }
})

watch(
  () => chat.isStreaming,
  (streaming) => pulse.setLive(streaming)
)

/** 自动朗读（与 AI解惑页同一约定：生成→结束的真实转变才读） */
watch(
  () => chat.isStreaming,
  (streaming, prev) => {
    if (prev && !streaming && autoRead.enabled.value) {
      const last = chat.messages[chat.messages.length - 1]
      if (last && last.role === 'assistant' && last.finishStatus === 1 && last.content) {
        speak(last.content)
      }
    }
  }
)

/** 内容总长（自动滚动监听，同 AI解惑页） */
const contentWatcher = computed(() => {
  let total = chat.messages.length
  for (const m of chat.messages) total += m.content.length
  return total
})
watch(contentWatcher, () => void nextTick(() => scrollToBottom(false)))

function onScroll(): void {
  const el = scroller.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

function scrollToBottom(force: boolean): void {
  const el = scroller.value
  if (!el) return
  if (force || stickToBottom.value) {
    el.scrollTop = el.scrollHeight
    stickToBottom.value = true
  }
}

async function onSend(text: string): Promise<void> {
  await chat.sendMessage(text)
  input.value?.focus()
}

function onStop(): void {
  chat.stopStream()
  input.value?.focus()
}

function onRetry(id: number): void {
  void chat.retryMessage(id)
}

function onResend(id: number): void {
  void chat.resendFromMessage(id)
}

function onFeedback(id: number, type: FeedbackType): void {
  void chatApi.sendFeedback(id, type)
}

async function confirmReminder(message: LocalMessage): Promise<void> {
  const r = message.healthReminder
  if (!r) return
  const value = Number(r.statedValue)
  if (Number.isFinite(value) && r.statedValue.trim() !== '') {
    try {
      await createHealthMetric({ type: r.field, value })
      showMessage('已更新健康指标', 'success')
    } catch {
      /* 拦截器已提示 */
    }
    return
  }
  // 非数值陈述（C2 前端解法）：写入健康备忘 KV（#66）——契约内接口
  try {
    await createHealthKv(r.field, r.statedValue)
    showMessage('已写入健康备忘', 'success')
    message.healthReminder = null
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<template>
  <div class="mx-auto flex h-full max-w-chat flex-col px-4">
    <div class="flex shrink-0 items-center justify-between gap-3 py-3">
      <div class="min-w-0">
        <h1 class="truncate text-section font-semibold text-ink">{{ currentTitle }}</h1>
        <p class="text-hint text-ink-muted">回答结合你的健康画像、指标与备忘，检索含你的资料库</p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <router-link
          to="/health/detail"
          class="rounded-btn border border-line px-3 py-1.5 text-hint text-ink-muted hover:border-brand hover:text-brand"
        >
          完善个人健康信息
        </router-link>
        <router-link
          to="/health"
          class="rounded-btn border border-line px-3 py-1.5 text-hint text-ink-muted hover:border-brand hover:text-brand"
        >
          返回
        </router-link>
      </div>
    </div>

    <div class="relative min-h-0 flex-1">
      <div ref="scroller" class="h-full overflow-y-auto" @scroll="onScroll">
        <div class="py-4">
          <MessageSkeleton v-if="loading" />
          <p v-else-if="chat.messages.length === 0" class="py-10 text-center text-body text-ink-muted">
            说说你最近的身体状况，或直接提问——回答会结合你的健康信息与资料库。
          </p>
          <div v-else class="space-y-5" role="log" aria-live="polite">
            <MessageItem
              v-for="message in chat.messages"
              :key="message.id"
              :message="message"
              @retry="onRetry"
              @resend="onResend"
              @feedback="onFeedback"
              @send="onSend"
              @confirm-reminder="confirmReminder"
            />
          </div>
        </div>
      </div>
    </div>

    <MessageInput
      ref="input"
      :streaming="chat.isStreaming"
      @send="onSend"
      @stop="onStop"
    />
    <DisclaimerBar />
  </div>
</template>
