<script setup lang="ts">
/* ============================================================
 * 消息卡片 v2.1 —— 从「气泡聊天」到「证据卡片」
 *
 * 版式：助手回答为带头像列的全宽内容卡（长回答可读性优先），
 * 用户消息保持右侧气泡。
 *
 * v2.1 增量（架构师裁决落地）：
 *   - 时间线含折叠态：完成后收成一行可解释性摘要（微交互②）
 *   - 追问建议优先走服务端（02 §4.9），404/失败回退客户端派生（F-4）
 *   - 拒答（2002）/敏感拦截（2004）消息琥珀色描边区分（V2 契约）
 * ============================================================ */

import { computed, defineAsyncComponent, ref, watch } from 'vue'
import type { LocalMessage } from '@/stores/chat'
import ReferencePanel from '@/components/ReferencePanel.vue'
import StageTimeline from '@/components/StageTimeline.vue'
import SuggestionChips from '@/components/SuggestionChips.vue'
import LoadingDots from '@/components/LoadingDots.vue'
import { showMessage } from '@/utils/message'
import { useSpeech } from '@/composer/useSpeech'
import { fetchSuggestions } from '@/api/chat'

/**
 * Markdown 渲染异步加载：markdown-it + highlight.js ≈ 100KB，
 * 不进首屏关键路径（对话页是默认路由）。
 */
const MarkdownView = defineAsyncComponent(() => import('@/components/MarkdownView.vue'))

const props = withDefaults(
  defineProps<{
    message: LocalMessage
    /** 是否展示追问建议（仅最后一条完成的助手消息） */
    showSuggestions?: boolean
    /** 文档解惑工作区会话 id（file 引用溯源点击用，C6） */
    qaSessionId?: number | null
  }>(),
  { showSuggestions: false, qaSessionId: null }
)

const emit = defineEmits<{
  retry: [id: number]
  resend: [id: number]
  feedback: [id: number, type: 'useful' | 'useless' | 'wrong']
  send: [text: string]
  'confirm-reminder': [message: LocalMessage]
}>()

const { synthesisSupported, speaking, speak, stopSpeaking } = useSpeech()

const isUser = computed(() => props.message.role === 'user')

/** 服务端返回的真实消息（id > 0）；负数 id 是本地占位 */
const hasRealId = computed(() => props.message.id > 0)

/**
 * 中断的两种情形分开处理（沿用 v1 的诚实区分）：
 *  - 有真实 id → retryFromMessageId 原地重新解答
 *  - 仅本地占位 id → 「重新提问」重发新消息
 */
const isInterrupted = computed(
  () => props.message.finishStatus === 0 && props.message.content.length > 0
)

/** 拒答（2002）/ 敏感拦截（2004）：琥珀描边区分，不伪装成正常回答 */
const isRejected = computed(() => props.message.isRejected === 1)

/** 追问建议只对「已完成且有真实 id」的回答展示 */
const canSuggest = computed(
  () => props.showSuggestions && !isUser.value && !props.message.streaming && hasRealId.value
)

/* ---------------- 追问建议：服务端优先，客户端兜底（F-4） ---------------- */

const suggestions = ref<string[]>([])
/** 会话内缓存：切回已看过的回答不重复请求（后端本身也有 suggestions_json 缓存） */
const suggestionCache = new Map<number, string[]>()

function deriveLocalSuggestions(): string[] {
  const docBased = [...new Set(props.message.references.map((r) => r.doc))]
    .slice(0, 2)
    .map((doc) => `《${doc}》里还有哪些实用建议？`)
  const pool = [
    '这些建议如何落实到一日三餐？',
    '有哪些常见误区需要避免？',
    '出现哪些情况时必须就医？',
    '日常自查可以关注哪些指标？'
  ]
  return [...docBased, ...pool.slice(0, 4 - docBased.length)].slice(0, 4)
}

watch(
  canSuggest,
  async (active) => {
    if (!active) return
    const id = props.message.id
    const cached = suggestionCache.get(id)
    if (cached) {
      suggestions.value = cached
      return
    }
    try {
      const res = await fetchSuggestions(id)
      const list = (res.questions ?? []).filter((q) => q.trim()).slice(0, 4)
      if (list.length === 0) throw new Error('empty')
      suggestionCache.set(id, list)
      suggestions.value = list
    } catch {
      // 404（后端 M10 未就绪）或其他失败：客户端派生，功能不缺席
      suggestions.value = deriveLocalSuggestions()
    }
  },
  { immediate: true }
)

/** 对照解答分段（🟡B-27）：存在即逐段渲染为对比卡，替代单段 content */
const segments = computed(() => props.message.segments ?? [])

const hasSegments = computed(() => segments.value.length > 0)

const timeText = computed(() => {
  const raw = props.message.createTime
  if (!raw) return ''
  const d = new Date(raw)
  return Number.isNaN(d.getTime())
    ? ''
    : `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
})

async function copyAnswer(): Promise<void> {
  try {
    await navigator.clipboard.writeText(props.message.content)
    showMessage('已复制', 'success')
  } catch {
    showMessage('复制失败，请手动选择文本', 'warning')
  }
}

function toggleSpeak(): void {
  if (speaking.value) {
    stopSpeaking()
  } else {
    speak(props.message.content)
  }
}
</script>

<template>
  <article class="msg-in flex" :class="isUser ? 'justify-end' : 'justify-start gap-3'">
    <!-- 助手头像：双温区光珠 -->
    <span
      v-if="!isUser"
      class="mt-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-full grad-primary"
      aria-hidden="true"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M2 12h4l2-6 4 12 2-6h8" />
      </svg>
    </span>

    <div class="min-w-0" :class="isUser ? 'max-w-[85%]' : 'flex-1'">
      <!-- 用户气泡 -->
      <div
        v-if="isUser"
        class="inline-block whitespace-pre-wrap rounded-bubble rounded-tr-sm bg-brand-soft px-4 py-2.5 text-chat text-ink"
      >
        {{ message.content }}
      </div>

      <!-- 助手内容卡 -->
      <div v-else>
        <!-- RAG 阶段时间线（含完成后的折叠摘要，微交互②） -->
        <Transition name="fold" mode="out-in">
          <StageTimeline
            v-if="message.stage"
            :key="message.stage === 'done' ? 'summary' : 'full'"
            :stage="message.stage"
            :retrieved="message.retrieved ?? (message.references?.length ?? 0) > 0"
            :reference-count="message.references?.length ?? 0"
            :retrieve-ms="message.retrieveMs ?? null"
            :latency-ms="message.latencyMs ?? null"
            :first-delta-ms="message.firstDeltaMs ?? null"
            :rewritten-query="message.rewrittenQuery ?? null"
          />
        </Transition>

        <div
          class="rounded-bubble rounded-tl-sm border bg-surface px-4 py-3"
          :class="isRejected ? 'border-accent/50' : 'border-line'"
        >
          <div
            v-if="message.streaming && !message.content && !hasSegments"
            class="py-1"
          >
            <LoadingDots />
          </div>

          <!-- 对照解答（仅个人解惑触发）：系统版 / 融合个人版逐段对比卡 -->
          <div v-else-if="hasSegments" class="space-y-3">
            <div
              v-for="seg in segments"
              :key="seg.answerId"
              class="rounded-card border p-3"
              :class="seg.source === 'fused' ? 'border-brand/40 bg-brand-soft/40' : 'border-line'"
            >
              <p class="mb-1.5 flex items-center gap-2 text-hint">
                <span
                  class="rounded-full px-2 py-0.5"
                  :class="seg.source === 'fused' ? 'bg-brand-soft text-brand' : 'bg-surface-2 text-ink-muted'"
                >
                  {{ seg.label || (seg.source === 'fused' ? '个人版' : '系统版') }}
                </span>
                <span v-if="seg.source === 'fused'" class="text-ink-muted">结合了你的个人资料与档案</span>
              </p>
              <div :class="{ 'stream-caret': message.streaming && !seg.ended && seg === segments[segments.length - 1] }">
                <MarkdownView :content="seg.content" />
              </div>
            </div>
          </div>

          <!-- 流式光标挂在内容流末尾 -->
          <div v-else :class="{ 'stream-caret': message.streaming }">
            <MarkdownView :content="message.content" />
          </div>

          <!-- 健康矛盾提醒（🟡B-28 done.healthReminder）：确认后写入档案 -->
          <div
            v-if="message.healthReminder && !message.streaming"
            class="msg-in mt-3 rounded-card border border-warning bg-accent-soft/40 p-3"
            role="alert"
          >
            <p class="text-body text-ink">
              你提到的「{{ message.healthReminder.statedValue }}」与档案记录的
              「{{ message.healthReminder.recordedValue }}」不一致。
            </p>
            <div class="mt-2 flex gap-2">
              <button
                type="button"
                class="rounded-btn grad-primary px-3 py-1 text-hint"
                @click="emit('confirm-reminder', message)"
              >
                更新为陈述值
              </button>
              <button
                type="button"
                class="rounded-btn border border-line px-3 py-1 text-hint text-ink-muted"
                @click="message.healthReminder = null"
              >
                忽略
              </button>
            </div>
          </div>

          <p
            v-if="message.healthSuggestion && !message.streaming"
            class="mt-2 text-hint text-brand"
          >
            {{ message.healthSuggestion }}
          </p>

          <ReferencePanel :references="message.references" :qa-session-id="qaSessionId" />
        </div>

        <!-- 健康注入提示（02 v4.0 done.healthUsed）：以服务端为准，不按会话类型推断 -->
        <p
          v-if="message.healthUsed === true && !message.streaming"
          class="mt-1 flex items-center gap-1.5 text-hint text-brand"
        >
          <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-brand" aria-hidden="true" />
          本次回答已参考你的健康信息
          <router-link to="/health/detail" class="text-ink-muted underline underline-offset-2">查看</router-link>
        </p>

        <SuggestionChips
          v-if="canSuggest && !isInterrupted && suggestions.length"
          :items="suggestions"
          @send="(text) => emit('send', text)"
        />
      </div>

      <!-- 中断提示（文案为 e2e 契约，勿改） -->
      <div v-if="!isUser && isInterrupted" class="mt-1 flex items-center gap-2">
        <span class="text-hint text-warning">生成已中断</span>
        <button
          v-if="hasRealId"
          type="button"
          class="rounded-btn px-2 py-0.5 text-hint text-brand hover:bg-brand-soft"
          @click="emit('retry', message.id)"
        >
          重新解答
        </button>
        <button
          v-else
          type="button"
          class="rounded-btn px-2 py-0.5 text-hint text-brand hover:bg-brand-soft"
          @click="emit('resend', message.id)"
        >
          重新提问
        </button>
      </div>

      <!-- 操作行（仅已完成且有真实 id 的回答） -->
      <div
        v-if="!isUser && !message.streaming && hasRealId"
        class="mt-1 flex items-center gap-1 text-hint text-ink-muted"
      >
        <button
          type="button"
          class="rounded-btn px-2 py-0.5 hover:bg-brand-soft hover:text-brand"
          @click="copyAnswer"
        >
          复制
        </button>
        <button
          v-if="synthesisSupported"
          type="button"
          class="rounded-btn px-2 py-0.5 hover:bg-brand-soft hover:text-brand"
          @click="toggleSpeak"
        >
          {{ speaking ? '停止朗读' : '朗读' }}
        </button>
        <button
          type="button"
          class="rounded-btn px-2 py-0.5 hover:bg-brand-soft hover:text-brand"
          @click="emit('feedback', message.id, 'useful')"
        >
          有用
        </button>
        <button
          type="button"
          class="rounded-btn px-2 py-0.5 hover:bg-brand-soft hover:text-brand"
          @click="emit('feedback', message.id, 'useless')"
        >
          无用
        </button>
        <button
          type="button"
          class="rounded-btn px-2 py-0.5 hover:bg-brand-soft hover:text-brand"
          @click="emit('retry', message.id)"
        >
          重新解答
        </button>
        <span v-if="timeText" class="tnum ml-1">{{ timeText }}</span>
      </div>
    </div>
  </article>
</template>

<style scoped>
/* 时间线折叠转场（微交互②） */
.fold-enter-active,
.fold-leave-active {
  transition:
    max-height 0.25s ease,
    opacity 0.25s ease;
  overflow: hidden;
}
.fold-enter-from,
.fold-leave-to {
  max-height: 0;
  opacity: 0;
}
.fold-enter-to,
.fold-leave-from {
  max-height: 80px;
  opacity: 1;
}
</style>
