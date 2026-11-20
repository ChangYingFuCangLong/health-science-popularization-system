<script setup lang="ts">
/* ============================================================
 * 文档解惑工作区（智能健康科普 · /knowledge/qa/:sessionId，02 v4.0 §五 #69-75）
 *
 * 双栏：左 = 会话文件集（上传[动态追加]/进度轮询/移除）；右 = 对话区。
 * 检索范围 = 本会话文件集（后端 DocumentSetScope 硬限定），流式协议零新参数。
 * 行为裁决落地：空文件集提问 → 400「请先上传文件」（前端预先禁用引导）；
 * 存在处理中文件时移除/删除 → 400（按钮禁用）；全部处理中时输入框提示等待。
 * ============================================================ */

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { usePulse } from '@/composer/usePulse'
import type { FeedbackType } from '@/api/chat'
import * as chatApi from '@/api/chat'
import * as kbQa from '@/api/kbQa'
import { validateFile } from '@/api/userKb'
import { UPLOAD_ACCEPT, UPLOAD_TYPES_LABEL } from '@/constants/upload'
import MessageItem from '@/components/MessageItem.vue'
import MessageInput from '@/components/MessageInput.vue'
import MessageSkeleton from '@/components/MessageSkeleton.vue'
import DisclaimerBar from '@/components/DisclaimerBar.vue'
import { showMessage } from '@/utils/message'
import { formatSize } from '@/api/userKb'

const route = useRoute()
const chat = useChatStore()
const pulse = usePulse()

const scroller = ref<HTMLElement | null>(null)
const input = ref<InstanceType<typeof MessageInput> | null>(null)
const loading = ref(false)
const stickToBottom = ref(true)

const sessionId = computed<number | null>(() => {
  const id = Number(route.params.sessionId)
  return Number.isFinite(id) && id > 0 ? id : null
})

const session = ref<kbQa.QaSession | null>(null)
const files = ref<kbQa.QaDocument[]>([])
const filesLoading = ref(false)

/* ---------------- 文件集 ---------------- */

const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const uploadPercent = ref<number | null>(null)
/** 轮询句柄：documentId → timer（同会话可能多文件并行解析） */
const pollers = new Map<number, number>()

const readyCount = computed(() => files.value.filter((f) => f.status === 2).length)

async function loadSessionInfo(): Promise<void> {
  if (sessionId.value == null) return
  try {
    const list = await kbQa.listQaSessions({ silent: true })
    session.value = list.find((s) => s.id === sessionId.value) ?? null
  } catch {
    session.value = null
  }
}

async function loadFiles(): Promise<void> {
  if (sessionId.value == null) return
  filesLoading.value = true
  try {
    files.value = await kbQa.listQaDocuments(sessionId.value, { silent: true })
  } catch {
    files.value = []
  } finally {
    filesLoading.value = false
  }
}

function statusText(s: number): string {
  return s === 2 ? '已就绪' : s === 3 ? '失败' : s === 1 ? '解析中' : '待处理'
}

async function onPickFile(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || sessionId.value == null) return
  const problem = validateFile(file)
  if (problem) {
    showMessage(problem, 'warning')
    return
  }
  uploading.value = true
  uploadPercent.value = 0
  try {
    const res = await kbQa.uploadQaDocument(sessionId.value, file, {
      onProgress: (p) => (uploadPercent.value = p < 0 ? -1 : p)
    })
    showMessage('已受理，正在解析', 'success')
    await loadFiles()
    startPolling(res.documentId)
  } catch {
    /* 2006/2007/400：拦截器已提示 */
  } finally {
    uploading.value = false
    uploadPercent.value = null
  }
}

/** 1.5s 轮询单文件进度（12 号 §三），status ∈ {2,3} 停止并刷新文件集 */
function startPolling(documentId: number): void {
  if (pollers.has(documentId)) return
  const timer = window.setInterval(async () => {
    try {
      const p = await kbQa.getQaDocumentProgress(documentId, { silent: true })
      if (p.status === 2 || p.status === 3) {
        window.clearInterval(timer)
        pollers.delete(documentId)
        if (p.status === 3) showMessage(`解析失败：${p.failReason || '未知原因'}`, 'error')
        await loadFiles()
      }
    } catch {
      /* 单次失败静默，下一轮重试 */
    }
  }, 1500)
  pollers.set(documentId, timer)
}

async function removeFile(doc: kbQa.QaDocument): Promise<void> {
  if (doc.status === 0 || doc.status === 1) return // 处理中：契约 400，按钮已禁用
  try {
    await kbQa.deleteQaDocument(doc.id)
    await loadFiles()
  } catch {
    /* 拦截器已提示 */
  }
}

/* ---------------- 对话（复用 chat store，sessionId 即 file 会话） ---------------- */

const sessionIdRef = computed(() => sessionId.value)

watch(
  sessionIdRef,
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

watch(
  () => chat.isStreaming,
  (v) => pulse.setLive(v)
)

const contentWatcher = computed(() => {
  let total = chat.messages.length
  for (const m of chat.messages) total += m.content.length
  return total
})
watch(contentWatcher, () => void nextTick(() => scrollToBottom(false)))

const inputDisabled = computed(() => readyCount.value === 0)
async function onSend(text: string): Promise<void> {
  if (inputDisabled.value) {
    showMessage('请先上传文件（解析完成后即可提问）', 'warning')
    return
  }
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

onMounted(() => {
  void loadSessionInfo()
  void loadFiles()
})

onBeforeUnmount(() => {
  pollers.forEach((t) => window.clearInterval(t))
  pollers.clear()
})
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex shrink-0 items-center justify-between gap-3 border-b border-line px-4 py-3">
      <div class="min-w-0">
        <h1 class="truncate text-section font-semibold text-ink">
          {{ session?.title ?? '文档解惑' }}
        </h1>
        <p class="text-hint text-ink-muted">回答只来源于本会话上传的文件（动态追加即时生效）</p>
      </div>
      <router-link
        to="/knowledge"
        class="shrink-0 rounded-btn border border-line px-3 py-1.5 text-hint text-ink-muted hover:border-brand hover:text-brand"
      >
        返回列表
      </router-link>
    </div>

    <div class="flex min-h-0 flex-1 flex-col gap-4 p-4 lg:flex-row">
      <!-- 左：文件集 -->
      <aside class="flex w-full shrink-0 flex-col rounded-card border border-line bg-surface p-4 lg:w-80">
        <div class="flex items-center justify-between">
          <h2 class="text-body font-medium text-ink">会话文件</h2>
          <span class="tnum text-hint text-ink-muted">{{ files.length }}/10</span>
        </div>

        <input ref="fileInput" type="file" :accept="UPLOAD_ACCEPT" class="hidden" @change="onPickFile" />
        <button
          type="button"
          class="mt-3 w-full rounded-btn grad-primary px-3 py-2 text-body disabled:opacity-40"
          :disabled="uploading || files.length >= 10"
          @click="fileInput?.click()"
        >
          {{ uploading ? (uploadPercent != null && uploadPercent >= 0 ? `上传中 ${uploadPercent}%` : '上传中…') : `上传文件（${UPLOAD_TYPES_LABEL}）` }}
        </button>

        <p v-if="filesLoading" class="py-4 text-center text-hint text-ink-muted">加载中…</p>
        <p v-else-if="files.length === 0" class="py-6 text-center text-hint text-ink-muted">
          还没有文件。上传后即可围绕内容提问。
        </p>

        <ul v-else class="mt-3 min-h-0 flex-1 space-y-2 overflow-y-auto">
          <li
            v-for="doc in files"
            :key="doc.id"
            class="rounded-card border border-line p-2.5"
          >
            <p class="flex items-center gap-2">
              <span class="min-w-0 flex-1 truncate text-body text-ink">{{ doc.title }}</span>
              <span
                class="shrink-0 rounded-full px-1.5 py-0.5 text-[10px]"
                :class="doc.status === 2 ? 'bg-brand-soft text-brand' : doc.status === 3 ? 'bg-accent-soft text-accent' : 'bg-surface-2 text-ink-muted'"
              >
                {{ statusText(doc.status) }}
              </span>
            </p>
            <p class="mt-1 flex items-center gap-2 text-hint text-ink-muted">
              <span>{{ formatSize(doc.fileSize) }}</span>
              <span v-if="doc.status === 2">· {{ doc.chunkCount }} 块</span>
              <button
                type="button"
                class="ml-auto rounded-btn px-1.5 py-0.5 hover:text-danger disabled:opacity-40"
                :disabled="doc.status === 0 || doc.status === 1"
                title="处理中的文件不能移除（契约 400）"
                @click="removeFile(doc)"
              >
                移除
              </button>
            </p>
            <p v-if="doc.status === 3 && doc.failReason" class="mt-1 text-hint text-danger">
              {{ doc.failReason }}
            </p>
          </li>
        </ul>

        <p class="mt-3 border-t border-line pt-2 text-hint text-ink-muted/80">
          单文件 ≤20MB 且 ≤500 块；每会话 ≤10 份。文件内容不会进入通用解惑。
        </p>
      </aside>

      <!-- 右：对话区（复用对话组件） -->
      <section class="flex min-h-0 flex-1 flex-col">
        <div class="relative min-h-0 flex-1">
          <div ref="scroller" class="h-full overflow-y-auto" @scroll="onScroll">
            <div class="mx-auto max-w-chat px-2 py-4">
              <MessageSkeleton v-if="loading" />
              <p v-else-if="chat.messages.length === 0" class="py-10 text-center text-body text-ink-muted">
                {{ inputDisabled ? '上传文件并等待解析完成后开始提问' : '针对文件内容提问，例如「总结这几份材料的要点」' }}
              </p>
              <div v-else class="space-y-5" role="log" aria-live="polite">
                <MessageItem
                  v-for="message in chat.messages"
                  :key="message.id"
                  :message="message"
                  :qa-session-id="sessionId"
                  @retry="onRetry"
                  @resend="onResend"
                  @feedback="onFeedback"
                  @send="onSend"
                />
              </div>
            </div>
          </div>
        </div>

        <MessageInput
          ref="input"
          :streaming="chat.isStreaming"
          :disabled="inputDisabled"
          @send="onSend"
          @stop="onStop"
        />
        <DisclaimerBar />
      </section>
    </div>
  </div>
</template>
