<script setup lang="ts">
/* ============================================================
 * 知识探索 v2.1（V2 契约 + 微交互①）
 *
 * v2.1 增量（架构师裁决落地）：
 *   - 详情 sections 渲染（02 §5.3：结构化分块文档按章节分组；V1 历史文档整体渲染）
 *   - 「继续了解」优先走相关文章接口（02 §5.4 pgvector 质心近邻，带相似度），
 *     404 / 空 → 回退同分类客户端派生（F-4）
 *   - 引用溯源高亮：从回答引用卡片带 highlight 参数进入时，
 *     原文命中片段高亮闪烁并滚动定位（微交互①）
 *
 * 数据层沿用静默降级：后端未就绪时空态说明，不弹错误。
 * e2e 契约：文章卡片是 button；正文以纯文本渲染；不出现「加载失败」。
 * ============================================================ */

import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as knowledgeApi from '@/api/knowledge'
import type { ArticleDetail, KnowledgeArticle, KnowledgeCategory, RelatedArticle } from '@/api/knowledge'
import * as kbQa from '@/api/kbQa'
import type { QaSession } from '@/api/kbQa'
import { showMessage } from '@/utils/message'
import { UPLOAD_TYPES_LABEL } from '@/constants/upload'

/* ---------------- 文档解惑（02 v4.0 §五 #69-75，12 号设计） ---------------- */
const qaSessions = ref<QaSession[]>([])
const qaCreating = ref(false)
const qaConfirmId = ref<number | null>(null)

async function loadQaSessions(): Promise<void> {
  try {
    qaSessions.value = await kbQa.listQaSessions({ silent: true })
  } catch {
    qaSessions.value = [] // B-31 未实现：静默降级
  }
}

async function createQaSession(): Promise<void> {
  qaCreating.value = true
  try {
    const created = await kbQa.createQaSession()
    void router.push(`/knowledge/qa/${created.id}`)
  } catch {
    /* 2006 配额：拦截器已提示 */
  } finally {
    qaCreating.value = false
  }
}

async function removeQaSession(s: QaSession): Promise<void> {
  if (qaConfirmId.value !== s.id) {
    qaConfirmId.value = s.id
    window.setTimeout(() => {
      if (qaConfirmId.value === s.id) qaConfirmId.value = null
    }, 3000)
    return
  }
  qaConfirmId.value = null
  try {
    await kbQa.deleteQaSession(s.id)
    await loadQaSessions()
  } catch (e) {
    if (e instanceof Error && e.message.includes('处理中')) showMessage(e.message, 'warning')
  }
}
import * as kbApi from '@/api/userKb'
import MyKbPanel from '@/components/MyKbPanel.vue'

const route = useRoute()
const router = useRouter()

/* ---------------- Tab：权威知识库 / 我的资料 ----------------
 * 两个「知识层」并列展示：前者是机构审定的语料，后者是用户上传的增量层。
 * 用 Tab 而不是新增导航项——移动端底部标签栏已是 4 项满配，加第 5 项会挤到不可用。
 * Tab 状态放进 query（?tab=mine）而不是纯本地 state：可刷新可回退可分享。
 */
const TABS = [
  { key: 'global' as const, label: '权威知识库' },
  { key: 'mine' as const, label: '我的资料' }
]
type TabKey = (typeof TABS)[number]['key']

const activeTab = computed<TabKey>(() => (route.query.tab === 'mine' ? 'mine' : 'global'))

function setTab(key: TabKey): void {
  if (key === activeTab.value) return
  void router.push(key === 'mine' ? '/knowledge?tab=mine' : '/knowledge')
}

/** 个人库文档数（Tab 徽标）。拿不到就不显示徽标，而不是显示 0 撒谎 */
const personalCount = ref(0)

const categories = ref<KnowledgeCategory[]>([])
const articles = ref<KnowledgeArticle[]>([])
const keyword = ref('')
const activeCategory = ref('')
const detail = ref<ArticleDetail | null>(null)
const loading = ref(false)
/** 后端是否已提供知识浏览能力（未就绪时页面降级为空态） */
const knowledgeReady = ref(true)
/** 详情面板阅读进度（0~100，仅宽屏面板内滚动时更新） */
const readingPct = ref(0)

/** 文章详情 ID 来自 URL（面板形态，不占独立路由页） */
const articleId = computed<number | null>(() => {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(id) && id > 0 ? id : null
})

/** 引用卡片带进来的高亮片段（回答引用的 digest 原文） */
const highlightQuery = computed<string>(() => {
  const raw = route.query.highlight
  const text = Array.isArray(raw) ? raw[0] : raw
  return typeof text === 'string' && text.trim() ? text.trim() : ''
})

/* ---------------- 数据加载 ---------------- */

async function loadCategories(): Promise<void> {
  try {
    categories.value = await knowledgeApi.listCategories({ silent: true })
  } catch {
    categories.value = []
    knowledgeReady.value = false
  }
}

async function loadArticles(): Promise<void> {
  loading.value = true
  try {
    const page = await knowledgeApi.listArticles(
      {
        category: activeCategory.value || undefined,
        keyword: keyword.value.trim() || undefined,
        pageNum: 1,
        pageSize: 20
      },
      { silent: true }
    )
    articles.value = page.records
    knowledgeReady.value = true
  } catch {
    articles.value = []
    knowledgeReady.value = false
  } finally {
    loading.value = false
  }
}

async function loadDetail(id: number): Promise<void> {
  try {
    detail.value = await knowledgeApi.getArticle(id, { silent: true })
  } catch {
    detail.value = null
  }
}

function openArticle(id: number): void {
  void router.push(`/knowledge/${id}`)
}

function closeDetail(): void {
  void router.push('/knowledge')
}

/* ---------------- 相关文章：服务端优先，同分类兜底（F-4） ---------------- */

const related = ref<RelatedArticle[]>([])
const relatedFromServer = ref(false)

async function loadRelated(id: number): Promise<void> {
  try {
    const res = await knowledgeApi.listRelatedArticles(id, 4, { silent: true })
    const list = (res.articles ?? []).filter((a) => a.id !== id).slice(0, 4)
    if (list.length > 0) {
      related.value = list
      relatedFromServer.value = true
      return
    }
  } catch {
    /* 404（M12 未就绪）或其他失败：走同分类派生 */
  }
  related.value = articles.value
    .filter((a) => a.id !== id && a.category === detail.value?.category)
    .slice(0, 4)
    .map((a) => ({ id: a.id, title: a.title, category: a.category, similarity: -1 }))
  relatedFromServer.value = false
}

/* ---------------- 高亮定位（微交互①） ---------------- */

interface BodyPart {
  text: string
  hit: boolean
}
interface BodyBlock {
  title: string | null
  parts: BodyPart[]
}

/** 把全文 / sections 切成「命中 + 前后文」片段；digest 可能被截断，先整串、再前缀匹配 */
const bodyBlocks = computed<BodyBlock[]>(() => {
  const d = detail.value
  if (!d) return []
  const kw = highlightQuery.value
  const sources: Array<{ title: string | null; content: string }> =
    d.sections && d.sections.length > 1
      ? d.sections.map((s) => ({ title: s.title, content: s.content }))
      : [{ title: null, content: d.content }]

  return sources.map((src) => {
    let idx = -1
    let len = 0
    if (kw) {
      idx = src.content.indexOf(kw)
      len = kw.length
      if (idx < 0 && kw.length > 16) {
        const prefix = kw.replace(/…/g, '').slice(0, 16)
        idx = src.content.indexOf(prefix)
        len = prefix.length
      }
    }
    if (idx < 0) return { title: src.title, parts: [{ text: src.content, hit: false }] }
    return {
      title: src.title,
      parts: [
        { text: src.content.slice(0, idx), hit: false },
        { text: src.content.slice(idx, idx + len), hit: true },
        { text: src.content.slice(idx + len), hit: false }
      ]
    }
  })
})

const detailBodyEl = ref<HTMLElement | null>(null)

watch(
  () => [detail.value, highlightQuery.value] as const,
  async () => {
    if (!detail.value || !highlightQuery.value) return
    await nextTick()
    const hit = detailBodyEl.value?.querySelector('mark.hl-flash')
    hit?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
)

function onDetailScroll(e: Event): void {
  const el = e.target as HTMLElement
  const max = el.scrollHeight - el.clientHeight
  readingPct.value = max > 0 ? Math.min(100, Math.round((el.scrollTop / max) * 100)) : 0
}

watch(activeCategory, () => void loadArticles())
watch(articleId, async (id) => {
  detail.value = null
  related.value = []
  readingPct.value = 0
  if (id != null) {
    await loadDetail(id)
    void loadRelated(id)
  }
})

/** 权威语料只在真正需要时加载：停在「我的资料」Tab 时不去请求无关数据 */
const globalLoaded = ref(false)

async function loadGlobal(): Promise<void> {
  if (globalLoaded.value) return
  await loadCategories()
  await loadArticles()
  globalLoaded.value = true
  if (articleId.value != null) {
    await loadDetail(articleId.value)
    void loadRelated(articleId.value)
  }
}

async function loadPersonalCount(): Promise<void> {
  try {
    const s = await kbApi.getStats({ silent: true })
    personalCount.value = s.documentCount
  } catch {
    personalCount.value = 0
  }
}

watch(activeTab, (tab) => {
  if (tab === 'global') void loadGlobal()
})

onMounted(async () => {
  void loadPersonalCount()
  if (activeTab.value === 'global') await loadGlobal()
})
</script>

<template>
  <div class="mx-auto max-w-list px-4 py-6">
    <h1 class="text-display font-semibold text-ink">知识探索</h1>
    <p class="mt-1 text-body text-ink-muted">
      回答中引用的科普语料都来自这里——溯源，从一条出处开始。
    </p>

    <!-- 文档解惑（#69-75）：上传文件 → 只基于文件集问答 -->
    <section class="mt-4 rounded-card border border-line bg-surface p-4">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 class="text-section font-medium text-ink">文档解惑</h2>
          <p class="text-hint text-ink-muted">
            上传文件（{{ UPLOAD_TYPES_LABEL }}），提问只基于这些文件；可动态追加，回答引用标注来源文件。
          </p>
        </div>
        <button
          type="button"
          class="rounded-btn grad-primary px-4 py-2 text-body"
          :disabled="qaCreating"
          @click="createQaSession"
        >
          {{ qaCreating ? '创建中…' : '新建解惑会话' }}
        </button>
      </div>

      <ul v-if="qaSessions.length" class="mt-3 divide-y divide-line">
        <li v-for="s in qaSessions" :key="s.id" class="flex items-center gap-3 py-2.5">
          <button type="button" class="min-w-0 flex-1 text-left" @click="router.push(`/knowledge/qa/${s.id}`)">
            <span class="block truncate text-body font-medium text-ink">{{ s.title }}</span>
            <span class="tnum mt-0.5 block text-hint text-ink-muted">
              {{ s.documentCount }} 份文件<template v-if="s.processingCount > 0">（{{ s.processingCount }} 份解析中）</template>
              · {{ s.messageCount }} 条消息
            </span>
          </button>
          <button
            type="button"
            class="shrink-0 rounded-btn px-2 py-1 text-hint transition-colors"
            :class="qaConfirmId === s.id ? 'font-medium text-danger' : 'text-ink-muted hover:text-danger'"
            :disabled="s.processingCount > 0"
            :title="s.processingCount > 0 ? '存在解析中文件，暂不能删除' : ''"
            @click="removeQaSession(s)"
          >
            {{ qaConfirmId === s.id ? '确认删除' : '删除' }}
          </button>
        </li>
      </ul>
      <p v-else class="mt-3 text-hint text-ink-muted/80">
        还没有解惑会话。多文件问答正是检索增强的不可替代场景（12 号 §二）。
      </p>
    </section>

    <!-- 两个知识层并列：机构审定的权威语料 + 你上传的增量层 -->
    <div class="mt-4 flex items-center gap-1 border-b border-line" role="tablist" aria-label="知识来源">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        role="tab"
        :aria-selected="activeTab === t.key"
        class="relative -mb-px px-3 py-2 text-body transition-colors max-md:min-h-11"
        :class="activeTab === t.key ? 'text-brand' : 'text-ink-muted hover:text-ink'"
        @click="setTab(t.key)"
      >
        {{ t.label }}
        <span
          v-if="t.key === 'mine' && personalCount > 0"
          class="tnum ml-1 rounded-full bg-surface-2 px-1.5 py-0.5 text-[11px] text-ink-muted"
        >
          {{ personalCount }}
        </span>
        <span
          v-if="activeTab === t.key"
          class="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-brand"
          aria-hidden="true"
        />
      </button>
    </div>

    <template v-if="activeTab === 'global'">
    <!-- 宽屏：双栏；窄屏：单栏列表 + 详情覆盖层 -->
    <div v-if="detail" class="mt-4 grid gap-4 xl:grid-cols-5">
      <!-- 阅读面板 -->
      <section class="xl:col-span-3 xl:sticky xl:top-20 xl:self-start">
        <div
          class="relative rounded-card border border-line bg-surface p-6 xl:max-h-[calc(100dvh-10rem)] xl:overflow-y-auto"
          @scroll="onDetailScroll"
        >
          <!-- 阅读进度 -->
          <div class="sticky top-0 -mx-6 -mt-6 mb-4 h-1 bg-transparent">
            <div
              class="h-full rounded-full transition-[width] duration-150"
              :style="{
                width: `${readingPct}%`,
                backgroundImage: 'linear-gradient(90deg, var(--grad-a), var(--grad-b))'
              }"
            />
          </div>

          <div class="flex items-start justify-between gap-4">
            <div>
              <h2 class="text-section font-semibold text-ink">{{ detail.title }}</h2>
              <p class="mt-1 text-hint text-ink-muted">
                {{ detail.category }}<span v-if="detail.source"> · 来源：{{ detail.source }}</span>
              </p>
            </div>
            <button
              type="button"
              class="shrink-0 rounded-btn border border-line px-3 py-1 text-body text-ink-muted hover:border-brand hover:text-brand"
              @click="closeDetail"
            >
              返回列表
            </button>
          </div>

          <!-- 正文：sections 优先（02 §5.3），整体全文兜底；命中片段高亮（微交互①） -->
          <article ref="detailBodyEl" class="mt-4 space-y-4 text-body leading-8 text-ink">
            <section v-for="(block, bi) in bodyBlocks" :key="bi">
              <h3 v-if="block.title" class="mb-2 text-section font-medium text-ink">
                {{ block.title }}
              </h3>
              <p class="whitespace-pre-wrap">
                <template v-for="(p, pi) in block.parts" :key="pi">
                  <mark v-if="p.hit" class="hl-flash">{{ p.text }}</mark>
                  <template v-else>{{ p.text }}</template>
                </template>
              </p>
            </section>
          </article>

          <!-- 继续了解：服务端相关推荐（带相似度）/ 同分类派生 -->
          <div v-if="related.length" class="mt-6 border-t border-line pt-4">
            <p class="text-hint text-ink-muted">继续了解</p>
            <div class="mt-2 flex flex-wrap gap-2">
              <button
                v-for="a in related"
                :key="a.id"
                type="button"
                class="rounded-full border border-line px-3 py-1 text-hint text-ink-muted hover:border-brand hover:text-brand"
                @click="openArticle(a.id)"
              >
                {{ a.title }}
                <span v-if="relatedFromServer && a.similarity >= 0" class="tnum text-brand">
                  {{ Math.round(a.similarity * 100) }}%
                </span>
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- 列表 -->
      <div class="xl:col-span-2">
        <div class="flex flex-wrap items-center gap-2">
          <button
            type="button"
            class="rounded-full px-3 py-2 text-body sm:py-1"
            :class="activeCategory === '' ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
            @click="activeCategory = ''"
          >
            全部
          </button>
          <button
            v-for="c in categories"
            :key="c.name"
            type="button"
            class="rounded-full px-3 py-2 text-body sm:py-1"
            :class="activeCategory === c.name ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
            @click="activeCategory = c.name"
          >
            {{ c.name }}（{{ c.count }}）
          </button>

          <input
            v-model="keyword"
            type="search"
            placeholder="搜索标题关键词"
            class="ml-auto w-full rounded-input border border-line bg-surface px-3 py-2.5 text-body text-ink outline-none focus:border-brand sm:w-40 sm:py-1"
            @keyup.enter="loadArticles"
          />
        </div>

        <p v-if="loading" class="mt-6 text-body text-ink-muted">加载中…</p>

        <div
          v-else-if="!knowledgeReady"
          class="mt-6 rounded-card border border-line bg-surface p-6 text-body text-ink-muted"
        >
          知识库接口尚未就绪，页面已降级为空态；接口联通后此处将展示全部语料。
        </div>

        <div v-else-if="articles.length === 0" class="mt-6 text-body text-ink-muted">
          暂无内容。知识库文档需在管理端上传并完成解析后才会出现。
        </div>

        <div v-else class="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-1">
          <button
            v-for="a in articles"
            :key="a.id"
            type="button"
            class="card-lift rounded-card border p-4 text-left"
            :class="a.id === articleId ? 'border-brand bg-brand-soft' : 'border-line bg-surface'"
            @click="openArticle(a.id)"
          >
            <span class="block text-body font-medium text-ink">{{ a.title }}</span>
            <span class="mt-1 block text-hint text-ink-muted">
              {{ a.category }}<span v-if="a.source"> · {{ a.source }}</span
              ><span v-if="a.chunkCount"> · 共 {{ a.chunkCount }} 段</span>
            </span>
          </button>
        </div>
      </div>
    </div>

    <!-- 无详情：列表占满 -->
    <template v-else>
      <div class="mt-4 flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="rounded-full px-3 py-1 text-body"
          :class="activeCategory === '' ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
          @click="activeCategory = ''"
        >
          全部
        </button>
        <button
          v-for="c in categories"
          :key="c.name"
          type="button"
          class="rounded-full px-3 py-1 text-body"
          :class="activeCategory === c.name ? 'grad-primary' : 'text-ink-muted hover:bg-surface-2'"
          @click="activeCategory = c.name"
        >
          {{ c.name }}（{{ c.count }}）
        </button>

        <input
          v-model="keyword"
          type="search"
          placeholder="搜索标题关键词"
          class="ml-auto w-full rounded-input border border-line bg-surface px-3 py-2.5 text-body text-ink outline-none focus:border-brand sm:w-40 sm:py-1"
          @keyup.enter="loadArticles"
        />
      </div>

      <p v-if="loading" class="mt-6 text-body text-ink-muted">加载中…</p>

      <div
        v-else-if="!knowledgeReady"
        class="mt-6 rounded-card border border-line bg-surface p-6 text-body text-ink-muted"
      >
        知识库接口尚未就绪，页面已降级为空态；接口联通后此处将展示全部语料。
      </div>

      <div v-else-if="articles.length === 0" class="mt-6 text-body text-ink-muted">
        暂无内容。知识库文档需在管理端上传并完成解析后才会出现。
      </div>

      <div v-else class="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <button
          v-for="a in articles"
          :key="a.id"
          type="button"
          class="card-lift rounded-card border border-line bg-surface p-4 text-left"
          @click="openArticle(a.id)"
        >
          <span class="block text-body font-medium text-ink">{{ a.title }}</span>
          <span class="mt-1 block text-hint text-ink-muted">
            {{ a.category }}<span v-if="a.source"> · {{ a.source }}</span
            ><span v-if="a.chunkCount"> · 共 {{ a.chunkCount }} 段</span>
          </span>
        </button>
      </div>
    </template>
    </template>

    <!-- 我的资料：个人知识库（契约 docs/V2/07，后端未实现时由 dev mock 供数） -->
    <MyKbPanel v-if="activeTab === 'mine'" />
  </div>
</template>
