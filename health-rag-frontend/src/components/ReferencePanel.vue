<script setup lang="ts">
/* ============================================================
 * 引用来源面板 v2（v2 创新点 2：证据链闭环）
 *
 * 相比 v1 的两处升级：
 *   1. 相关度评分可视化——score 渲染为双温区渐变评分条，
 *      「这条证据有多可信」从数字变成一眼可读的仪表
 *   2. 点击直达原文——v1 里 goto(chunk) 是个空实现（void chunk.docId），
 *      v2 真正跳转 /knowledge/:docId，形成「回答 → 证据 → 原文」闭环。
 *      这依赖后端 reference.chunks[].docId（ADR-019 的溯源契约）。
 * ============================================================ */

import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ReferenceChunk } from '@/api/types'

const props = withDefaults(
  defineProps<{ references: ReferenceChunk[]; /** 所处文档解惑工作区（file 引用点击回工作区用） */ qaSessionId?: number | null }>(),
  { qaSessionId: null }
)
const open = ref(false)
const router = useRouter()

/**
 * 来源归属（02 v4.0 ReferenceChunk.scope 三值）：
 *   global   权威知识库——机构审定语料
 *   personal 我的资料——用户自己上传的增量层，未经机构审定
 *   file     文档解惑的会话文件——doc 字段即上传文件名，不属于权威库
 * 历史消息没有该字段，兜底为 global；否则老会话会整片被标成「我的资料」。
 */
function scopeOf(chunk: ReferenceChunk): 'global' | 'personal' | 'file' {
  if (chunk.scope === 'personal') return 'personal'
  if (chunk.scope === 'file') return 'file'
  return 'global'
}

const personalCount = computed(() => props.references.filter((r) => scopeOf(r) === 'personal').length)

/**
 * 相关度展示（F3 修复）：score 语义随检索策略而变——
 *   naive  → 0~1 余弦相似度，绝对百分比有意义；
 *   hybrid → RRF 分（1/(60+rank) ≈ 0.016 量级），绝对百分比全部被钳成同一个数，
 *            且对 top1 归一化也无济于事（相邻位次分数几乎持平）。
 * 启发式分流：max(score) > 0.1 视为余弦型 → 绝对百分比；
 * 否则视为 RRF 型 → 不显示百分比，条长对 top1 归一（诚实呈现"逐位次递减"的平缓），
 * 位次本身由序号徽标承担。
 */
const maxScore = computed(() =>
  props.references.reduce((m, r) => (r.score > m ? r.score : m), 0)
)

const isCosineLike = computed(() => maxScore.value > 0.1)

function barPct(score: number): number {
  if (!Number.isFinite(score) || maxScore.value <= 0) return 8
  const v = isCosineLike.value ? score : score / maxScore.value
  const pct = v <= 1 ? v * 100 : v
  return Math.min(100, Math.max(8, Math.round(pct)))
}

function scoreLabel(score: number): string {
  if (!isCosineLike.value) return ''
  const pct = score <= 1 ? score * 100 : score
  return Number.isFinite(pct) ? `${Math.round(pct)}%` : ''
}

function goto(chunk: ReferenceChunk): void {
  if (chunk.docId <= 0) return
  // file 文档不属于权威库（无 /knowledge/:id 页面）：回到所属解惑工作区查看文件集
  if (scopeOf(chunk) === 'file') {
    if (props.qaSessionId) void router.push(`/knowledge/qa/${props.qaSessionId}`)
    return
  }
  const kw = chunk.digest?.trim()

  // 个人资料不在权威知识库里——它没有 /knowledge/:id 这个页面。
  // 因此溯源目标必须分流：个人资料跳到「我的资料」Tab 并直接展开该文档的分块预览；
  // 权威文档沿用原有行为（打开原文页并高亮命中片段）。
  if (scopeOf(chunk) === 'personal') {
    void router.push({
      path: '/knowledge',
      query: { tab: 'mine', doc: String(chunk.docId), ...(kw ? { hl: kw } : {}) }
    })
    return
  }

  // 溯源闭环 + 微交互①：digest 作为高亮参数，原文页定位到命中片段并闪烁标记
  void router.push(
    kw ? `/knowledge/${chunk.docId}?highlight=${encodeURIComponent(kw)}` : `/knowledge/${chunk.docId}`
  )
}
</script>

<template>
  <section v-if="props.references && props.references.length" class="mt-3">
    <button
      type="button"
      class="inline-flex items-center gap-1.5 rounded-btn border border-line px-2.5 py-1 text-hint text-ink-muted transition-colors hover:border-brand hover:text-brand"
      :aria-expanded="open"
      @click="open = !open"
    >
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <path d="M9 18l6-6-6-6" />
      </svg>
      {{ open ? '收起' : '查看' }}依据来源（{{ props.references.length }}）
      <span v-if="personalCount > 0" class="text-ink-muted">
        · 含 {{ personalCount }} 条我的资料
      </span>
    </button>

    <ul v-if="open" class="mt-2 grid gap-2 sm:grid-cols-2">
      <li v-for="(chunk, i) in props.references" :key="chunk.chunkId">
        <!-- 来源区分手法：权威库实线、我的资料虚线——零新增色值的语义对比，
             避免为了「个人 / 权威」再引入第三种强调色（手册：单屏一个强调色） -->
        <button
          type="button"
          class="card-lift w-full rounded-card border bg-canvas px-3 py-2.5 text-left"
          :class="scopeOf(chunk) === 'personal' ? 'border-dashed border-line-strong' : scopeOf(chunk) === 'file' ? 'border-dashed border-accent/40' : 'border-line'"
          :disabled="!(chunk.docId > 0) || scopeOf(chunk) === 'file'"
          @click="goto(chunk)"
        >
          <span class="flex items-center gap-2">
            <span
              class="tnum flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[11px] grad-primary"
            >
              {{ i + 1 }}
            </span>
            <span class="block truncate font-medium text-ink">{{ chunk.doc }}</span>
            <span v-if="chunk.docId > 0 && scopeOf(chunk) !== 'file'" class="ml-auto shrink-0 text-hint text-brand">
              {{ scopeOf(chunk) === 'personal' ? '看分块 →' : '原文 →' }}
            </span>
          </span>
          <span class="mt-1 flex items-center gap-2">
            <span
              class="shrink-0 rounded-full border px-1.5 py-0.5 text-[11px]"
              :class="
                scopeOf(chunk) === 'personal'
                  ? 'border-dashed border-line-strong text-ink-muted'
                  : scopeOf(chunk) === 'file'
                    ? 'border-dashed border-accent/40 text-accent'
                    : 'border-line text-ink-muted'
              "
            >
              {{ scopeOf(chunk) === 'personal' ? '我的资料' : scopeOf(chunk) === 'file' ? '你的文件' : '权威知识库' }}
            </span>
            <span v-if="chunk.section" class="min-w-0 truncate text-hint text-ink-muted">
              {{ chunk.section }}
            </span>
          </span>
          <span class="mt-1 line-clamp-2 block text-hint text-ink-muted">{{ chunk.digest }}</span>
          <span class="mt-1.5 flex items-center gap-2">
            <span class="h-1 flex-1 overflow-hidden rounded-full bg-surface-2">
              <span
                class="block h-full rounded-full"
                :style="{
                  width: `${barPct(chunk.score)}%`,
                  backgroundImage: 'linear-gradient(90deg, var(--grad-a), var(--grad-b))'
                }"
              />
            </span>
            <span v-if="scoreLabel(chunk.score)" class="tnum shrink-0 text-hint text-ink-muted">
              {{ scoreLabel(chunk.score) }}
            </span>
          </span>
        </button>
      </li>
    </ul>

    <!-- 个人资料未经机构审定：给一句责任提示，而不是让用户以为它同样权威 -->
    <p v-if="open && personalCount > 0" class="mt-2 text-hint text-ink-muted/80">
      标为「我的资料」的内容来自你自己上传的文件，未经权威机构审定，涉及健康决策请以权威来源或医生意见为准。
    </p>
  </section>
</template>
