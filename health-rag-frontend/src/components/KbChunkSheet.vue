<script setup lang="ts">
/* ============================================================
 * 个人知识库 · 分块预览
 *
 * 这不是一个「技术细节面板」，而是本功能最有价值的透明化手段：
 * 用户上传了一份 PDF，凭什么相信系统"读懂"了它？
 * 让他亲眼看到文件被切成什么、检索会拿到哪一段，疑虑自然消解。
 * 因此面板顶部有一句解释（分块 → 检索 → 交给大模型），而不是只有列表。
 *
 * 外壳（遮罩 / 方向 / ESC / 滚动锁定）由 SideSheet 统一承担，
 * 本组件只负责"读什么、画什么"。
 * ============================================================ */

import { computed, nextTick, ref, watch } from 'vue'
import * as kbApi from '@/api/userKb'
import type { PersonalChunk } from '@/api/userKb'
import SideSheet from '@/components/SideSheet.vue'

const props = defineProps<{
  open: boolean
  docId: number | null
  docTitle: string
  /**
   * 从引用卡片带进来的命中片段（digest 原文）。digest 可能被截断，
   * 因此先整串匹配、失败再退化到前 16 字前缀（与 KnowledgeView 的高亮策略一致）。
   */
  highlight?: string
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

/** 高亮关键字（已归一化），无则返回空串 */
const keyword = computed(() => {
  const raw = props.highlight?.replace(/…/g, '').trim() ?? ''
  if (!raw) return ''
  return raw.length > 16 ? raw.slice(0, 16) : raw
})

/** 把分块正文切成「命中 + 前后文」三段，供模板渲染 <mark> */
function partsOf(content: string): Array<{ text: string; hit: boolean }> {
  const kw = keyword.value
  if (!kw) return [{ text: content, hit: false }]
  const idx = content.indexOf(kw)
  if (idx < 0) return [{ text: content, hit: false }]
  return [
    { text: content.slice(0, idx), hit: false },
    { text: content.slice(idx, idx + kw.length), hit: true },
    { text: content.slice(idx + kw.length), hit: false }
  ]
}

function hasHit(c: PersonalChunk): boolean {
  return keyword.value !== '' && c.content.includes(keyword.value)
}

const chunks = ref<PersonalChunk[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const failed = ref(false)
const pageNum = ref(1)
const pages = ref(1)
const total = ref(0)

const hasMore = computed(() => pageNum.value < pages.value)

/**
 * 请求序号：只有最新一次请求的响应可以落地。
 *
 * 为什么必须有它：ensureFound 会在「第 1 页没找到命中片段」时自己往后翻页，
 * 于是同一时刻可能有两个请求在飞。若不做守卫，先发的那个会把后发的结果覆盖掉
 * （表现为：高亮定位偶发失效，取决于两个响应的先后，与网络快慢耦合）。
 */
let loadSeq = 0

async function load(reset: boolean): Promise<void> {
  const id = props.docId
  if (id == null) return

  const seq = ++loadSeq
  if (reset) {
    loading.value = true
    failed.value = false
    chunks.value = []
    pageNum.value = 1
  } else {
    loadingMore.value = true
  }
  const targetPage = pageNum.value

  try {
    const res = await kbApi.listChunks(id, { pageNum: targetPage, pageSize: 20 }, { silent: true })
    // 已有更新的请求发出 → 本次结果作废（否则会把新请求的页码/数据搅乱）
    if (seq !== loadSeq) return
    chunks.value = reset ? res.records : [...chunks.value, ...res.records]
    pages.value = res.pages
    total.value = res.total
  } catch {
    // 静默失败：面板内给可读的降级说明，不弹全局错误
    if (seq === loadSeq && reset) failed.value = true
  } finally {
    if (seq === loadSeq) {
      loading.value = false
      loadingMore.value = false
    }
  }
  if (reset && seq === loadSeq) void ensureFound()
}

function loadMore(): void {
  if (!hasMore.value || loadingMore.value) return
  pageNum.value += 1
  void load(false)
}

/* ---------------- 命中定位 ----------------
 * 进入分块预览的入口有两种：用户主动点「查看分块」（无命中目标），
 * 以及从回答的引用卡片跳进来（带 digest 作目标）。
 * 后者必须真的把用户送到那一段——否则「溯源」只是看起来闭环。
 * 命中片段可能不在第一页，故逐页加载直到找到（上限 6 页，防止长文档把面板拖死）。
 */

const listEl = ref<HTMLElement | null>(null)

async function ensureFound(): Promise<void> {
  if (!keyword.value) return
  let guard = 0
  while (guard < 6 && !chunks.value.some(hasHit) && pageNum.value < pages.value) {
    pageNum.value += 1
    await load(false)
    guard += 1
  }
  await nextTick()
  const el = listEl.value?.querySelector('[data-hit="true"]')
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

/**
 * 单一 watcher 观察 (open, docId) 这一对状态。
 *
 * ⚠️ 不能拆成两个 watcher：`sheet = { open: true, docId: id }` 会让两个 prop 在同一
 * tick 内同时变化，于是两个 watcher 各触发一次 load(true) —— 双请求并发，
 * 加上 ensureFound 的自动翻页，谁先返回谁的数据就留下（实测表现为高亮定位偶发失效）。
 * 合并成一个 watcher 后，Vue 的批量调度保证一次变化只加载一次。
 */
watch(
  () => [props.open, props.docId] as const,
  ([open, docId], prev) => {
    const [prevOpen, prevDocId] = prev ?? [false, null]
    if (open && (!prevOpen || docId !== prevDocId)) void load(true)
  },
  { immediate: true }
)
</script>

<template>
  <SideSheet :open="open" :label="`${docTitle} 的分块预览`" @close="emit('close')">
    <header class="flex shrink-0 items-start gap-3 border-b border-line px-4 py-3">
      <div class="min-w-0 flex-1">
        <h2 class="truncate text-section font-medium text-ink">{{ docTitle }}</h2>
        <p class="mt-0.5 text-hint text-ink-muted">
          共 {{ total }} 个分块 · 检索命中哪一段，就把那一段交给大模型作答
        </p>
      </div>
      <button
        type="button"
        class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-line text-ink-muted transition-colors hover:border-brand hover:text-brand max-md:h-11 max-md:w-11"
        aria-label="关闭分块预览"
        @click="emit('close')"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </header>

    <div class="min-h-0 flex-1 overflow-y-auto pb-safe">
      <!-- 加载骨架 -->
      <div v-if="loading" class="space-y-3 p-4" aria-busy="true">
        <div v-for="i in 3" :key="i" class="rounded-card border border-line bg-surface p-4">
          <div class="shimmer h-3 w-24 rounded-full" />
          <div class="shimmer mt-3 h-3 w-full rounded-full" />
          <div class="shimmer mt-2 h-3 w-5/6 rounded-full" />
          <div class="shimmer mt-2 h-3 w-2/3 rounded-full" />
        </div>
      </div>

      <div v-else-if="failed" class="p-4">
        <p class="rounded-card border border-line bg-surface p-4 text-body text-ink-muted">
          该文档的分块暂时读不到——可能仍在索引中，或后端接口未就绪。
        </p>
        <button
          type="button"
          class="mt-3 rounded-btn border border-line px-3 py-2 text-body text-ink-muted hover:border-brand hover:text-brand max-md:min-h-11"
          @click="load(true)"
        >
          重试
        </button>
      </div>

      <div v-else-if="chunks.length === 0" class="p-6 text-center text-body text-ink-muted">
        这篇文档还没有可预览的分块。
      </div>

      <ol v-else ref="listEl" class="space-y-3 p-4">
        <li
          v-for="c in chunks"
          :key="c.id"
          class="rounded-card border p-4"
          :class="hasHit(c) ? 'border-brand/60 bg-surface' : 'border-line bg-surface'"
          :data-hit="hasHit(c) ? 'true' : 'false'"
        >
          <div class="flex items-center gap-2">
            <span
              class="font-display tnum flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-line bg-surface-2 text-[11px] text-ink-muted"
            >
              {{ c.chunkIndex + 1 }}
            </span>
            <span v-if="c.section" class="min-w-0 flex-1 truncate text-hint text-ink-muted">
              {{ c.section }}
            </span>
            <span class="tnum ml-auto shrink-0 text-hint text-ink-muted/70">
              {{ c.charCount }} 字
            </span>
          </div>
          <p class="mt-2 whitespace-pre-wrap text-body leading-7 text-ink">
            <template v-for="(p, pi) in partsOf(c.content)" :key="pi">
              <mark v-if="p.hit" class="hl-flash">{{ p.text }}</mark>
              <template v-else>{{ p.text }}</template>
            </template>
          </p>
        </li>
      </ol>

      <div v-if="!loading && !failed && hasMore" class="px-4 pb-4">
        <button
          type="button"
          class="w-full rounded-btn border border-line py-2 text-body text-ink-muted transition-colors hover:border-brand hover:text-brand disabled:opacity-40 max-md:min-h-11"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? '加载中…' : '加载更多分块' }}
        </button>
      </div>
    </div>
  </SideSheet>
</template>
