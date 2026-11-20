<script setup lang="ts">
/* ============================================================
 * AI解惑页（智能健康科普 · 模块页签①，02 v4.0 §四）
 *
 * 只承载 kind='normal' 的通用解惑：会话列表恒 normal（后端过滤 +
 * 前端兜底过滤双保险），新建恒普通对话，个人解惑的一切在 /health 模块。
 *
 * 沿用三条经过实测的约定（不推翻，已验证的行为是资产）：
 *   1. URL 是会话状态的唯一真相：切换会话一律 router.push
 *   2. 自动滚动状态机：stickToBottom 由 scroll 事件维护
 *   3. watcher 监听「消息条数 + 全部内容长度」
 * ============================================================ */

import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatStore, type LocalMessage } from '@/stores/chat'
import { usePulse } from '@/composer/usePulse'
import type { FeedbackType } from '@/api/chat'
import * as chatApi from '@/api/chat'
import { createHealthMetric, createHealthKv } from '@/api/health'
import SessionSidebar from '@/components/SessionSidebar.vue'
import MessageItem from '@/components/MessageItem.vue'
import MessageInput from '@/components/MessageInput.vue'
import MessageSkeleton from '@/components/MessageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DisclaimerBar from '@/components/DisclaimerBar.vue'
import CrisisNotice from '@/components/CrisisNotice.vue'
import { useAutoRead } from '@/composer/useAutoRead'
import { useSpeech } from '@/composer/useSpeech'
import { showMessage } from '@/utils/message'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const pulse = usePulse()
const autoRead = useAutoRead()
const { speak } = useSpeech()

const scroller = ref<HTMLElement | null>(null)
const input = ref<InstanceType<typeof MessageInput> | null>(null)
const loading = ref(false)

/** 后端 kind 过滤上线前的兜底：通用解惑列表绝不混入 health/file 会话 */
const normalSessions = computed(() => chat.sessions.filter((s) => (s.kind ?? 'normal') === 'normal'))

/**
 * 健康矛盾提醒（done.healthReminder）确认 → 写档案。
 * 数值型陈述 → 按指标 key 写健康指标；非数值 → 引导去健康详情页手动更新。
 * 仅个人解惑会出现该提醒；此处通用处理，写失败由拦截器提示。
 */
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
  // 非数值陈述（C2 前端解法）：写入健康备忘 KV（#66）——契约内接口，不需要新端点
  try {
    await createHealthKv(r.field, r.statedValue)
    showMessage('已写入健康备忘', 'success')
    message.healthReminder = null
  } catch {
    /* 拦截器已提示（2006 上限等） */
  }
}

/* ---------------- 移动端会话抽屉 ----------------
 * 改造前的四个缺口（手机端「抽屉拉不回去」的直接成因）：
 *   ① 无关闭按钮 —— 只能点右侧残存的遮罩窄条（375px 屏上仅剩 115px）
 *   ② 无 ESC 关闭、③ 路由切换不自动收起
 *   ④ 打开时消息区仍可随手指滑动（滚动穿透）
 * 本轮补齐「收回」的最后两块：
 *   ⑤ 收回时面板滑出（此前只有滑入，关闭瞬间消失，手感断裂）
 *   ⑥ 手势收回：按住面板向左拖，跟手移动；过 28% 宽度松手即收回，
 *      否则弹回。纵向意图交还给原生滚动（touch-action: pan-y + 意图判定），
 *      拖拽结束后的残余 click 在捕获阶段拦截，避免误触会话行。
 */
const sidebarOpen = ref(false)
const sidebarTrigger = ref<HTMLButtonElement | null>(null)
const sidebarClose = ref<HTMLButtonElement | null>(null)
const drawerEl = ref<HTMLElement | null>(null)

/* ---------- 手势收回状态（保持简单：距离阈值判定，不做速度采样） ---------- */
const dragX = ref(0)
const dragSettling = ref(false)
let dragStartX = 0
let dragStartY = 0
/** false=尚未判定方向；true=已判定为横向（进入拖拽）；'v'=已判定为纵向（还给滚动） */
let dragIntent: 'unset' | 'h' | 'v' = 'unset'
/** 抽屉正在滑出（忽略后续 move，避免二次进入拖拽） */
let dragClosed = false
/** 拖拽手势结束后拦截随后派发的那一次 click */
let dragSuppressClick = false

const panelStyle = computed(() => {
  if (dragX.value === 0 && !dragSettling.value) return undefined
  return {
    transform: `translateX(${dragX.value}px)`,
    transition: dragSettling.value
      ? 'transform 0.26s cubic-bezier(0.16, 1, 0.3, 1)'
      : 'none'
  }
})

function onPanelPointerdown(e: PointerEvent): void {
  if (e.pointerType === 'mouse' && e.button !== 0) return
  dragStartX = e.clientX
  dragStartY = e.clientY
  dragIntent = 'unset'
  dragSuppressClick = false
  dragClosed = false // 新手势开始：上次手势的滑出标志必须复位，否则拖拽整个失灵
  // ⚠️ 此处不能 setPointerCapture：捕获会把后续 click 一并重定向到面板，
  // 面板里的 ✕ / 会话行将收不到任何点击（真机实测踩中）。捕获推迟到
  // pointermove 确认「横向收回意图」之后，纯点击路径完全不受影响。
}

function onPanelPointermove(e: PointerEvent): void {
  if (dragClosed) return
  const dx = e.clientX - dragStartX
  const dy = e.clientY - dragStartY
  if (dragIntent === 'unset') {
    if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return
    // 横向且向左（收回方向）才接管；纵向意图直接放行原生滚动
    if (Math.abs(dx) > Math.abs(dy) * 1.2 && dx < 0) {
      dragIntent = 'h'
      dragSettling.value = false
      // 此刻才捕获：保证拖出面板边界的 move/up 不丢；click 重定向由
      // dragSuppressClick 在捕获阶段拦截
      ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
    } else {
      dragIntent = 'v'
    }
  }
  if (dragIntent === 'h') dragX.value = Math.min(0, dx)
}

function onPanelPointerup(): void {
  if (dragIntent !== 'h') return
  dragSuppressClick = true
  dragIntent = 'unset'
  const w = drawerEl.value?.offsetWidth ?? 300
  if (dragX.value <= -w * 0.28) {
    dragClosed = true
    dragSettling.value = true
    dragX.value = -w - 32 // 完全滑出屏幕外
    // 等滑出动画走完再摘除抽屉（遮罩随后淡出），保证「跟手 → 收回」连续
    window.setTimeout(() => {
      dragX.value = 0
      dragSettling.value = false
      closeSidebar()
    }, 240)
  } else {
    dragSettling.value = true
    dragX.value = 0 // 弹回
    window.setTimeout(() => {
      dragSettling.value = false
    }, 300)
  }
}

function onPanelPointercancel(): void {
  if (dragIntent !== 'h') return
  dragSuppressClick = true
  dragIntent = 'unset'
  dragSettling.value = true
  dragX.value = 0
  window.setTimeout(() => {
    dragSettling.value = false
  }, 300)
}

/** 拖拽过的手势在松手后仍会派发一次 click —— 在捕获阶段拦掉，防止误触会话行 */
function onPanelClickCapture(e: MouseEvent): void {
  if (dragSuppressClick) {
    dragSuppressClick = false
    e.stopPropagation()
    e.preventDefault()
  }
}

/** 移动端工具条展示的当前会话标题 */
const currentTitle = computed<string>(() => {
  const cur = chat.sessions.find((s) => s.id === chat.currentSessionId)
  return cur?.title ?? '新对话'
})

function openSidebar(): void {
  sidebarOpen.value = true
  // 打开后焦点移入抽屉 —— 键盘/读屏用户不必逐个 Tab 穿过整页
  void nextTick(() => sidebarClose.value?.focus())
}

function closeSidebar(): void {
  if (!sidebarOpen.value) return
  sidebarOpen.value = false
  void nextTick(() => sidebarTrigger.value?.focus())
}

function onDocKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') closeSidebar()
}

/** 视口回到桌面宽度时（旋转屏幕 / 放大窗口）收起抽屉，避免与常驻侧栏重叠 */
function onMqChange(e: MediaQueryListEvent): void {
  if (!e.matches) sidebarOpen.value = false
}

let mqMobile: MediaQueryList | null = null

/** 从 URL 解析会话 ID —— 单一真相来源 */
const sessionId = computed<number | null>(() => {
  const raw = route.params.sessionId
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(id) && id > 0 ? id : null
})

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

onMounted(async () => {
  await chat.loadSessions()

  // 健康中心「AI 解读」等外部入口的预填
  const prefill = chat.consumePrefill()
  if (prefill) {
    input.value?.setDraft(prefill)
    input.value?.focus()
  }

  window.addEventListener('keydown', onDocKeydown)
  mqMobile = window.matchMedia('(max-width: 767px)')
  mqMobile.addEventListener('change', onMqChange)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onDocKeydown)
  mqMobile?.removeEventListener('change', onMqChange)
})

/** 任何路由变化都收起抽屉（含浏览器前进/后退，这两个入口不经过 selectSession） */
watch(
  () => route.fullPath,
  () => {
    sidebarOpen.value = false
  }
)

/** 流式期间顶栏脉搏进入 live（签名动效：系统心跳加快） */
watch(
  () => chat.isStreaming,
  (streaming) => pulse.setLive(streaming)
)

/**
 * 自动朗读（F-5）：仅在「生成中 → 结束」的真实转变时触发，
 * 加载历史（isStreaming 恒为 false）不会误读；手动停止（finishStatus=0）不读。
 */
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

/*
 * 新消息到达【或正在生成的回答变长】时：仅当用户已在底部附近才自动滚动。
 * 监听值 = 消息条数 + 全部内容长度（流式期间条数不变、内容持续变长）。
 */
watch(
  () => {
    let total = chat.messages.length
    for (const m of chat.messages) total += m.content.length
    return total
  },
  () => {
    void nextTick(() => scrollToBottom(false))
  }
)

/**
 * 是否「贴底跟随」。由滚动事件维护的状态机：
 * 只有用户主动上翻才解除跟随，回到底部即恢复。
 * 响应式 ref：同时驱动「回到底部」悬浮按钮的显隐。
 */
const stickToBottom = ref(true)

function onScroll(): void {
  const el = scroller.value
  if (!el) return
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  stickToBottom.value = distance < 60
}

function scrollToBottom(force: boolean): void {
  const el = scroller.value
  if (!el) return
  if (force || stickToBottom.value) {
    el.scrollTop = el.scrollHeight
    stickToBottom.value = true
  }
}

/* ---------------- 交互 ---------------- */

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

/** 客户端中断（无真实 messageId）：以一条新消息重发 */
function onResend(id: number): void {
  void chat.resendFromMessage(id)
}

function onFeedback(id: number, type: FeedbackType): void {
  void chatApi.sendFeedback(id, type)
}

/** 最后一条消息是否为「已完成且有真实 id」的助手消息（追问建议的展示条件） */
const lastAssistantDone = computed<number | null>(() => {
  const list = chat.messages
  const last = list[list.length - 1]
  if (!last || last.role !== 'assistant' || last.streaming || last.id <= 0) return null
  if (last.finishStatus === 0) return null
  return last.id
})

function selectSession(id: number): void {
  void router.push(`/chat/${id}`)
  closeSidebar()
}

/**
 * 新建会话（docs/V2/08 §6.2）。
 *
 * AI解惑页的新建恒普通对话（02 v4.0 §1.5）：通用解惑由后端在首条提问时隐式创建，
 * 「新建对话」只是回到空态。个人解惑的新建入口在 /health 概览页——入口天然区分类型。
 */
function createSession(): void {
  closeSidebar()
  void router.push('/chat')
}

function renameSession(id: number, title: string): void {
  void chat.renameSession(id, title)
}

async function removeSession(id: number): Promise<void> {
  await chat.removeSession(id)
  if (chat.currentSessionId === null) void router.push('/chat')
}
</script>

<template>
  <div class="flex h-full">
    <!-- 侧栏：桌面常驻 260px；<768px 抽屉 -->
    <div class="hidden w-[260px] shrink-0 md:block">
      <SessionSidebar
        :sessions="normalSessions"
        :current-id="chat.currentSessionId"
        @select="selectSession"
        @create="createSession"
        @rename="renameSession"
        @remove="removeSession"
      />
    </div>

    <!-- 移动端抽屉：遮罩淡入 + 面板左侧滑入。
         宽度 min(82vw,300px)：保留足够遮罩面积供「点空白关闭」，
         同时在 375px 屏上仍能完整显示会话标题与操作按钮。 -->
    <Transition name="overlay">
      <div
        v-if="sidebarOpen"
        class="fixed inset-0 z-40 bg-black/50 backdrop-blur-[2px] md:hidden"
        @click="closeSidebar"
      >
        <div
          ref="drawerEl"
          class="drawer-panel flex h-full w-[min(82vw,300px)] flex-col bg-canvas shadow-2xl"
          :style="panelStyle"
          role="dialog"
          aria-modal="true"
          aria-label="会话列表"
          @click.stop
          @click.capture="onPanelClickCapture"
          @pointerdown="onPanelPointerdown"
          @pointermove="onPanelPointermove"
          @pointerup="onPanelPointerup"
          @pointercancel="onPanelPointercancel"
        >
          <!-- 抽屉头部：标题 + 显式关闭按钮（改造前无任何关闭入口） -->
          <div class="flex h-12 shrink-0 items-center justify-between border-b border-line pl-3 pr-1">
            <span class="text-body font-medium text-ink">会话</span>
            <button
              ref="sidebarClose"
              type="button"
              class="flex h-11 w-11 items-center justify-center rounded-btn text-ink-muted transition-colors active:bg-surface-2"
              aria-label="关闭会话列表"
              @click="closeSidebar"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- tabbar-safe：列表底部避开 iPhone Home 指示条 -->
          <div class="tabbar-safe min-h-0 flex-1">
            <SessionSidebar
              :sessions="normalSessions"
              :current-id="chat.currentSessionId"
              @select="selectSession"
              @create="createSession"
              @rename="renameSession"
              @remove="removeSession"
            />
          </div>
        </div>
      </div>
    </Transition>

    <!-- 主区 -->
    <div class="flex min-w-0 flex-1 flex-col">
      <!-- 模块内页签（11 号 §〇：智能健康科普 = AI解惑 + 健康科普） -->
      <div class="flex h-12 shrink-0 items-center gap-1 border-b border-line px-2">
        <span class="px-1 text-body font-medium text-ink max-sm:hidden">智能健康科普</span>
        <router-link
          to="/chat"
          class="rounded-full px-3 py-1 text-body"
          :class="route.path === '/chat' || route.path.startsWith('/chat/') ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
        >
          AI解惑
        </router-link>
        <router-link
          to="/knowledge"
          class="rounded-full px-3 py-1 text-body"
          :class="route.path.startsWith('/knowledge') ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
        >
          健康科普
        </router-link>
      </div>

      <!-- 移动端工具条：会话抽屉入口 + 当前会话标题（按钮 44px 触摸目标） -->
      <div class="flex h-12 shrink-0 items-center gap-1 border-b border-line px-1 md:hidden">
        <button
          ref="sidebarTrigger"
          type="button"
          class="flex h-11 shrink-0 items-center gap-1.5 rounded-btn px-3 text-body text-ink-muted transition-colors active:bg-surface-2"
          aria-label="打开会话列表"
          @click="openSidebar"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
            <path d="M3 6h18M3 12h18M3 18h18" />
          </svg>
          会话
        </button>
        <span class="min-w-0 flex-1 truncate pr-2 text-hint text-ink-muted">{{ currentTitle }}</span>
      </div>

      <div class="relative min-h-0 flex-1">
        <div
          ref="scroller"
          class="h-full"
          :class="sidebarOpen ? 'overflow-hidden' : 'overflow-y-auto'"
          @scroll="onScroll"
        >
          <div class="mx-auto max-w-chat px-4 py-4">
            <CrisisNotice :visible="chat.crisisVisible" @close="chat.dismissCrisis()" />

            <MessageSkeleton v-if="loading" />

            <EmptyState v-else-if="chat.messages.length === 0" @pick="onSend" />

            <!-- role="log" + aria-live="polite"：新回答可被屏幕阅读器播报 -->
            <div v-else class="space-y-5" role="log" aria-live="polite">
              <MessageItem
                v-for="message in chat.messages"
                :key="message.id"
                :message="message"
                :show-suggestions="message.id === lastAssistantDone"
                @retry="onRetry"
                @resend="onResend"
                @feedback="onFeedback"
                @send="onSend"
                @confirm-reminder="confirmReminder"
              />
            </div>
          </div>
        </div>

        <!-- 上翻查看历史时提供「回到底部」出口：不跟随 ≠ 迷路 -->
        <Transition name="jump">
          <button
            v-if="!stickToBottom && chat.messages.length > 0"
            type="button"
            class="jump-btn glass absolute bottom-4 left-1/2 flex h-9 w-9 items-center justify-center rounded-full border border-line text-ink-muted shadow-lg transition-colors hover:border-brand hover:text-brand"
            aria-label="回到底部"
            @click="scrollToBottom(true)"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 5v14M19 12l-7 7-7-7" />
            </svg>
          </button>
        </Transition>
      </div>

      <MessageInput
        ref="input"
        :streaming="chat.isStreaming"
        @send="onSend"
        @stop="onStop"
      />

      <!-- 免责声明常驻，不随消息滚动（§11.1，合规硬要求） -->
      <DisclaimerBar />
    </div>
  </div>
</template>

<style scoped>
/* 移动端抽屉：遮罩淡入淡出 + 面板滑入滑出（收回不再瞬间消失） */
.overlay-enter-active {
  transition: opacity 0.2s ease;
}
.overlay-leave-active {
  transition: opacity 0.24s ease;
}
.overlay-enter-from,
.overlay-leave-to {
  opacity: 0;
}

/* 面板滑入用 animation（挂载即播放一次）。
   缓动取 cubic-bezier(0.16, 1, 0.3, 1)：快起慢收，贴近「拉开抽屉」的手感；
   reduced-motion 由 main.css 的全局规则统一关闭。
   touch-action: pan-y —— 纵向滚动手势交给浏览器，横向拖拽由手势收回逻辑接管。 */
.drawer-panel {
  animation: drawer-in 0.3s cubic-bezier(0.16, 1, 0.3, 1);
  touch-action: pan-y;
}
@keyframes drawer-in {
  from {
    transform: translateX(-100%);
  }
}
/* 收回滑出：与遮罩淡出同拍（0.24s），方向相反 */
.overlay-leave-active .drawer-panel {
  animation: drawer-out 0.24s ease-in both;
}
@keyframes drawer-out {
  to {
    transform: translateX(-100%);
  }
}

/* 「回到底部」浮钮 */
/* 「回到底部」浮钮：居中偏移放在基础规则里，避免与过渡的 transform 打架 */
.jump-btn {
  transform: translateX(-50%);
}
.jump-enter-active,
.jump-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.jump-enter-from,
.jump-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}
</style>
