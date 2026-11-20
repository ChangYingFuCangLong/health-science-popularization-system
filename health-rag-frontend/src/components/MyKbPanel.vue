<script setup lang="ts">
/* ============================================================
 * 用户个人知识库 · 主面板
 *
 * 契约：docs/V2/07-待实现接口-用户个人知识库.md #44–#52（后端未实现，dev 走 mock）
 *
 * 这个面板要回答用户的三个问题，顺序即是页面顺序：
 *   1.「我还有多少额度？」  → 顶部配额仪表（文档数 / 存储 / 分块数）
 *   2.「怎么把我的东西放进去？」→ 上传投放区（空态时是页面主角）
 *   3.「它现在怎么样了？」  → 文档列表 + 真实进度 + 分块可查
 *
 * 三条实现约束：
 *   a. 进度轮询只处理 status=1 的文档，且只在存在这类文档时开启定时器 ——
 *      没有处理中的任务就不该有定时器在后台空转；
 *   b. 后端未就绪时整体降级为可读的空态（silent 请求），不弹错误刷屏；
 *   c. 上传进度、处理进度是两件事：前者是传输（客户端可知），
 *      后者是解析+向量化（服务端异步），UI 上必须分开表达，不能合成一个假进度条。
 * ============================================================ */

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as kbApi from '@/api/userKb'
import type { PersonalDocProgress, PersonalKbDoc, PersonalKbStats } from '@/api/userKb'
import KbDocCard from '@/components/KbDocCard.vue'
import KbUploadZone from '@/components/KbUploadZone.vue'
import KbChunkSheet from '@/components/KbChunkSheet.vue'
import { showMessage } from '@/utils/message'

const route = useRoute()
const router = useRouter()

/* ---------------- 数据 ---------------- */

const stats = ref<PersonalKbStats | null>(null)
const docs = ref<PersonalKbDoc[]>([])
const loading = ref(false)
/** 后端是否已提供个人库能力（未就绪时页面降级为空态说明） */
const ready = ref(true)

const keyword = ref('')
const statusFilter = ref<'' | '1' | '2' | '3'>('')
const uploadPercent = ref<number | null>(null)
const busyIds = ref<number[]>([])

/** 处理中文档的进度（#47），key = documentId */
const progressMap = ref<Record<number, PersonalDocProgress>>({})

const sheet = ref<{ open: boolean; docId: number | null; title: string }>({
  open: false,
  docId: null,
  title: ''
})
/** 从引用卡片带来的命中片段，透传给分块预览做高亮定位 */
const sheetHighlight = ref('')

const processingCount = computed(() => docs.value.filter((d) => d.status === 1).length)
const isEmpty = computed(() => docs.value.length === 0)

/* ---------------- 加载 ---------------- */

async function loadStats(): Promise<void> {
  try {
    stats.value = await kbApi.getStats({ silent: true })
    ready.value = true
  } catch {
    stats.value = null
    ready.value = false
  }
}

async function loadDocs(): Promise<void> {
  loading.value = true
  try {
    const page = await kbApi.listDocuments(
      {
        keyword: keyword.value.trim() || undefined,
        status: statusFilter.value ? (Number(statusFilter.value) as 1 | 2 | 3) : undefined,
        pageNum: 1,
        pageSize: 50
      },
      { silent: true }
    )
    docs.value = page.records
    ready.value = true
  } catch {
    docs.value = []
    ready.value = false
  } finally {
    loading.value = false
  }
}

async function refresh(): Promise<void> {
  await Promise.all([loadStats(), loadDocs()])
  syncPolling()
}

/* ---------------- 处理进度轮询 ---------------- */

let pollTimer: number | null = null
let ticking = false

async function pollOnce(): Promise<void> {
  if (ticking) return
  const pending = docs.value.filter((d) => d.status === 1)
  if (pending.length === 0) {
    stopPolling()
    return
  }
  ticking = true
  let finished = false
  try {
    const results = await Promise.all(
      pending.map((d) => kbApi.getProgress(d.id, { silent: true }).catch(() => null))
    )
    for (const p of results) {
      if (!p) continue
      progressMap.value = { ...progressMap.value, [p.documentId]: p }
      // 状态离开「处理中」= 这一篇尘埃落定，列表与概览都要跟着刷新
      if (p.status !== 1) finished = true
    }
  } finally {
    ticking = false
  }
  if (finished) {
    await refresh()
  }
}

function startPolling(): void {
  if (pollTimer !== null) return
  void pollOnce()
  pollTimer = window.setInterval(() => void pollOnce(), 1500)
}

function stopPolling(): void {
  if (pollTimer === null) return
  window.clearInterval(pollTimer)
  pollTimer = null
}

function syncPolling(): void {
  if (processingCount.value > 0) startPolling()
  else stopPolling()
}

/* ---------------- 上传 ---------------- */

async function onPick(file: File): Promise<void> {
  uploadPercent.value = 0
  try {
    await kbApi.uploadDocument(file, {
      onProgress: (p) => {
        uploadPercent.value = p
      }
    })
    showMessage(`《${file.name}》已受理，正在解析入库`, 'success')
    await refresh()
  } catch {
    // 失败文案由 Axios 拦截器统一弹出（含 2006 配额 / 2007 文件不符）
  } finally {
    uploadPercent.value = null
  }
}

/* ---------------- 文档操作 ---------------- */

function markBusy(id: number, on: boolean): void {
  busyIds.value = on ? [...busyIds.value, id] : busyIds.value.filter((x) => x !== id)
}

async function onRebuild(id: number): Promise<void> {
  markBusy(id, true)
  try {
    await kbApi.rebuildDocument(id)
    showMessage('已开始重新索引', 'success')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    markBusy(id, false)
  }
}

async function onRemove(id: number): Promise<void> {
  markBusy(id, true)
  try {
    await kbApi.deleteDocument(id)
    const next = { ...progressMap.value }
    delete next[id]
    progressMap.value = next
    showMessage('已删除，向量与文件一并清理', 'success')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    markBusy(id, false)
  }
}

function onPreview(id: number): void {
  const doc = docs.value.find((d) => d.id === id)
  sheet.value = { open: true, docId: id, title: doc?.title ?? '分块预览' }
  sheetHighlight.value = ''
}

function onCloseSheet(): void {
  sheet.value = { open: false, docId: null, title: '' }
  sheetHighlight.value = ''
  // 清掉入口参数：否则刷新页面又会把面板弹出来，且回退键语义变得难以预测
  if (route.query.doc || route.query.hl) {
    void router.replace({ path: '/knowledge', query: { tab: 'mine' } })
  }
}

/* ---------------- 外部入口：引用卡片「看分块 →」 ----------------
 * 引用卡片跳过来时带 doc（目标文档）与 hl（命中片段）。
 * 目标文档可能不在当前筛选结果里（用户可能先设了关键词或状态筛选），
 * 因此找不到时按契约 #45 重新拉一页大页宽去定位——而不是让入口静默失效。
 */

const focusDocId = computed<number | null>(() => {
  const raw = route.query.doc
  const v = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(v) && v > 0 ? v : null
})

const focusHighlight = computed<string>(() => {
  const raw = route.query.hl
  const s = Array.isArray(raw) ? raw[0] : raw
  return typeof s === 'string' ? s : ''
})

async function findDocAnywhere(id: number): Promise<PersonalKbDoc | null> {
  const local = docs.value.find((d) => d.id === id)
  if (local) return local
  try {
    const page = await kbApi.listDocuments({ pageNum: 1, pageSize: 200 }, { silent: true })
    return page.records.find((d) => d.id === id) ?? null
  } catch {
    return null
  }
}

async function applyFocus(): Promise<void> {
  const id = focusDocId.value
  if (id == null) return
  const doc = await findDocAnywhere(id)
  if (!doc) return
  await nextTick()
  sheetHighlight.value = focusHighlight.value
  sheet.value = { open: true, docId: id, title: doc.title }
}

/* ---------------- 偏好设置（#51 / #52） ---------------- */

const includeInChat = ref(true)
const savingSetting = ref(false)

async function loadSettings(): Promise<void> {
  try {
    const s = await kbApi.getSettings({ silent: true })
    includeInChat.value = s.includeInChat
  } catch {
    /* 未就绪则保持缺省 true，与服务端缺省一致 */
  }
}

async function toggleIncludeInChat(): Promise<void> {
  const next = !includeInChat.value
  includeInChat.value = next
  savingSetting.value = true
  try {
    await kbApi.updateSettings({ includeInChat: next })
    showMessage(next ? '个人资料将参与问答检索' : '个人资料不再参与问答', 'info')
  } catch {
    includeInChat.value = !next // 回滚
  } finally {
    savingSetting.value = false
  }
}


/* ---------------- 筛选交互 ---------------- */

let searchTimer: number | null = null
watch(keyword, () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => void loadDocs(), 320)
})

watch(statusFilter, () => void loadDocs())

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  await Promise.all([loadStats(), loadDocs(), loadSettings()])
  syncPolling()
  await applyFocus()
})

/** 已经在页面里时，从引用卡片再跳一次（query.doc 变化）同样要生效 */
watch(focusDocId, () => void applyFocus())

onBeforeUnmount(() => {
  stopPolling()
  if (searchTimer !== null) window.clearTimeout(searchTimer)
})

/* ---------------- 配额仪表（本页的记忆点） ---------------- */

function pct(used: number, max: number): number {
  if (!Number.isFinite(max) || max <= 0) return 0
  return Math.min(100, Math.round((used / max) * 100))
}

function tone(p: number): string {
  if (p >= 100) return 'bg-danger'
  if (p >= 80) return 'bg-warning'
  return 'bg-brand'
}

const meters = computed(() => {
  const q = stats.value?.quota
  if (!q) return []
  return [
    { label: '文档', used: q.usedDocuments, max: q.maxDocuments, unit: '篇' },
    { label: '占用', used: q.usedTotalSize, max: q.maxTotalSize, unit: 'bytes' },
    { label: '分块', used: q.usedTotalChunks, max: q.maxTotalChunks, unit: '段' }
  ]
})

function meterValue(used: number, unit: string): string {
  return unit === 'bytes' ? kbApi.formatSize(used) : `${used}`
}

function meterMax(max: number, unit: string): string {
  return unit === 'bytes' ? kbApi.formatSize(max) : `${max}`
}
</script>

<template>
  <div>
    <!-- 身份声明：一句话说清这个库的边界（隐私与隔离是它的立身之本） -->
    <div class="flex flex-wrap items-start gap-3">
      <div class="min-w-0 flex-1">
        <p class="flex items-center gap-2 text-body text-ink">
          <span
            class="rounded-full border border-dashed border-line-strong px-2 py-0.5 text-hint text-ink-muted"
          >
            我的资料
          </span>
          <span class="text-hint text-ink-muted">仅你可见 · 不影响管理端语料</span>
        </p>
      </div>
    </div>

    <!-- 配额仪表 -->
    <section
      v-if="stats"
      class="mt-4 grid gap-3 rounded-card border border-line bg-surface p-4 sm:grid-cols-3"
      aria-label="个人库用量"
    >
      <div v-for="m in meters" :key="m.label">
        <div class="flex items-baseline justify-between">
          <span class="text-hint text-ink-muted">{{ m.label }}</span>
          <span class="font-display tnum text-body text-ink">
            {{ meterValue(m.used, m.unit) }}
            <span class="text-hint text-ink-muted">/ {{ meterMax(m.max, m.unit) }}</span>
          </span>
        </div>
        <div class="mt-1.5 h-1 overflow-hidden rounded-full bg-surface-2">
          <div
            class="h-full rounded-full transition-[width] duration-500"
            :class="tone(pct(m.used, m.max))"
            :style="{ width: `${pct(m.used, m.max)}%` }"
          />
        </div>
      </div>
    </section>

    <!-- 上传 -->
    <div class="mt-4">
      <KbUploadZone
        :upload-percent="uploadPercent"
        :compact="!isEmpty"
        @pick="onPick"
        @error="(msg) => showMessage(msg, 'warning')"
      />
    </div>

    <!-- 状态总览：处理中/失败的数量给一个整体口径 -->
    <p v-if="stats && !isEmpty" class="mt-3 flex flex-wrap gap-x-3 text-hint text-ink-muted">
      <span class="tnum">已索引入库 {{ stats.readyCount }}</span>
      <span v-if="stats.processingCount > 0" class="tnum text-brand">
        · 处理中 {{ stats.processingCount }}
      </span>
      <span v-if="stats.failedCount > 0" class="tnum text-danger">
        · 失败 {{ stats.failedCount }}
      </span>
      <span v-if="stats.pendingChunks > 0" class="tnum">
        · 待向量化 {{ stats.pendingChunks }} 块
      </span>
    </p>

    <!-- 筛选 -->
    <div v-if="!isEmpty || statusFilter || keyword" class="mt-4 flex flex-wrap items-center gap-2">
      <button
        type="button"
        class="rounded-full px-3 py-2 text-body sm:py-1"
        :class="statusFilter === '' ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
        @click="statusFilter = ''"
      >
        全部
      </button>
      <button
        type="button"
        class="rounded-full px-3 py-2 text-body sm:py-1"
        :class="statusFilter === '2' ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
        @click="statusFilter = '2'"
      >
        已索引
      </button>
      <button
        type="button"
        class="rounded-full px-3 py-2 text-body sm:py-1"
        :class="statusFilter === '1' ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
        @click="statusFilter = '1'"
      >
        处理中
      </button>
      <button
        type="button"
        class="rounded-full px-3 py-2 text-body sm:py-1"
        :class="statusFilter === '3' ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
        @click="statusFilter = '3'"
      >
        失败
      </button>

      <input
        v-model="keyword"
        type="search"
        placeholder="搜索我的资料标题"
        class="ml-auto w-full rounded-input border border-line bg-surface px-3 py-2.5 text-body text-ink outline-none placeholder:text-ink-muted focus:border-brand sm:w-48 sm:py-1"
      />
    </div>

    <!-- 列表 -->
    <p v-if="loading" class="mt-6 text-body text-ink-muted">加载中…</p>

    <div
      v-else-if="!ready"
      class="mt-6 rounded-card border border-line bg-surface p-6 text-body text-ink-muted"
    >
      个人知识库接口尚未就绪，页面已降级为空态；接口联通后此处将展示你的资料。
    </div>

    <div
      v-else-if="docs.length === 0 && (keyword || statusFilter)"
      class="mt-6 text-body text-ink-muted"
    >
      没有匹配的资料。换个关键词，或清空筛选条件。
    </div>

    <div v-else-if="docs.length > 0" class="mt-4 grid gap-3 sm:grid-cols-2">
      <KbDocCard
        v-for="(doc, i) in docs"
        :key="doc.id"
        :doc="doc"
        :progress="progressMap[doc.id] ?? null"
        :busy="busyIds.includes(doc.id)"
        :index="i"
        @preview="onPreview"
        @rebuild="onRebuild"
        @remove="onRemove"
      />
    </div>

    <!-- 偏好：是否参与问答 -->
    <section
      v-if="!isEmpty"
      class="mt-6 rounded-card border border-line bg-surface p-4"
      aria-label="个人库偏好"
    >
      <label class="flex cursor-pointer items-start gap-3">
        <button
          type="button"
          role="switch"
          :aria-checked="includeInChat"
          :aria-busy="savingSetting"
          class="relative mt-0.5 h-5 w-9 shrink-0 rounded-full border transition-colors"
          :class="includeInChat ? 'border-brand bg-brand-soft' : 'border-line bg-surface-2'"
          @click="toggleIncludeInChat"
        >
          <span
            class="absolute top-0.5 h-3.5 w-3.5 rounded-full transition-all"
            :class="includeInChat ? 'left-[18px] bg-brand' : 'left-0.5 bg-ink-muted'"
          />
        </button>
        <span class="min-w-0 flex-1">
          <span class="block text-body text-ink">让「我的资料」参与问答检索</span>
          <span class="mt-0.5 block text-hint text-ink-muted">
            开启后，个人解惑（/health 模块）提问会同时检索权威知识库与你的资料，
            回答中的引用会标注来源。通用解惑与文档解惑不受此开关影响。
          </span>
        </span>
      </label>
    </section>

    <!-- 分块预览 -->
    <KbChunkSheet
      :open="sheet.open"
      :doc-id="sheet.docId"
      :doc-title="sheet.title"
      :highlight="sheetHighlight"
      @close="onCloseSheet"
    />
  </div>
</template>
