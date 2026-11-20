<script setup lang="ts">
/* ============================================================
 * 会话侧栏 v2 —— 「病历档案栏」
 *   搜索 + 按时间分组（今天 / 昨天 / 7 天内 / 更早）
 *   行内重命名沿用 v1；删除改两步确认（点击后 3 秒内再点生效）
 * e2e 契约：nav[aria-label="会话列表"] 的 button、文案「新建对话」
 *
 * 移动端适配：行高与操作按钮按 44px 触摸目标基准放大；
 *   重命名/删除由 .actions-reveal 承载 —— 触屏常显（触屏无 hover，
 *   改造前用 group-hover 控制 display，导致手机上这两个功能不可达）。
 * ============================================================ */

import { computed, ref } from 'vue'
import type { ChatSession } from '@/api/types'

const props = defineProps<{
  sessions: ChatSession[]
  currentId: number | null
}>()

const emit = defineEmits<{
  select: [id: number]
  remove: [id: number]
  rename: [id: number, title: string]
  create: []
}>()

const keyword = ref('')
const editingId = ref<number | null>(null)
const editingTitle = ref('')
/** 两步确认：进入「确认删除」状态的时间戳 */
const confirmSince = ref(0)

const filtered = computed(() => {
  const kw = keyword.value.trim()
  const list = kw ? props.sessions.filter((s) => s.title.includes(kw)) : props.sessions
  return [...list].sort((a, b) => b.updateTime.localeCompare(a.updateTime))
})

/** update-time → 分组名 */
function groupOf(updateTime: string): string {
  const t = new Date(updateTime).getTime()
  if (Number.isNaN(t)) return '更早'
  const now = Date.now()
  const day = 24 * 60 * 60 * 1000
  const startOfToday = new Date().setHours(0, 0, 0, 0)
  if (t >= startOfToday) return '今天'
  if (t >= startOfToday - day) return '昨天'
  if (now - t < 7 * day) return '7 天内'
  return '更早'
}

const groups = computed(() => {
  const map = new Map<string, ChatSession[]>()
  for (const s of filtered.value) {
    const key = groupOf(s.updateTime)
    const bucket = map.get(key)
    if (bucket) bucket.push(s)
    else map.set(key, [s])
  }
  return [...map.entries()]
})

/**
 * 渲染用分组：按时间分组（今天/昨天/7天内/更早）。
 * 用「分组数组」而不是两处重复渲染行标记 —— 会话行的标记有 40 余行，
 * 复制一份出来就一定会有一份先腐坏。
 */
const sections = computed(() =>
  [...groups.value].map(([label, list]) => ({ label, list }))
)

function startRename(session: ChatSession): void {
  editingId.value = session.id
  editingTitle.value = session.title
}

function commitRename(id: number): void {
  const title = editingTitle.value.trim()
  if (title) emit('rename', id, title)
  editingId.value = null
}

function onRemove(session: ChatSession): void {
  const now = Date.now()
  if (confirmSince.value && now - confirmSince.value < 3000) {
    confirmSince.value = 0
    emit('remove', session.id)
    return
  }
  confirmSince.value = now
  window.setTimeout(() => {
    if (confirmSince.value && Date.now() - confirmSince.value >= 2900) confirmSince.value = 0
  }, 3100)
}
</script>

<template>
  <aside class="flex h-full w-full flex-col border-r border-line bg-canvas/60">
    <div class="space-y-2 p-3">
      <button
        type="button"
        class="w-full rounded-btn grad-primary px-3 py-2.5 text-body transition-transform hover:scale-[1.01] active:scale-[0.98] md:py-2"
        @click="emit('create')"
      >
        新建对话
      </button>
      <input
        v-model="keyword"
        type="search"
        placeholder="搜索会话"
        class="w-full rounded-input border border-line bg-surface px-3 py-2.5 text-hint text-ink outline-none placeholder:text-ink-muted focus:border-brand md:py-1.5"
      />
    </div>

    <nav class="flex-1 overflow-y-auto px-2 pb-3" aria-label="会话列表">
      <p v-if="filtered.length === 0" class="px-3 py-6 text-center text-hint text-ink-muted">
        {{ keyword ? '没有匹配的会话' : '暂无会话，从一次提问开始' }}
      </p>

      <section v-for="sec in sections" :key="sec.label" class="mb-2">
        <p class="px-3 pb-1 pt-2 text-hint text-ink-muted/70">{{ sec.label }}</p>
        <button
          v-for="session in sec.list"
          :key="session.id"
          type="button"
          class="group mb-1 w-full rounded-btn border px-3 py-3 text-left text-body transition-colors md:py-2"
          :class="
            session.id === props.currentId
              ? 'border-brand/40 bg-brand-soft text-ink'
              : 'border-transparent text-ink hover:bg-surface'
          "
          @click="emit('select', session.id)"
        >
          <!-- 重命名：行内编辑 -->
          <input
            v-if="editingId === session.id"
            v-model="editingTitle"
            class="w-full rounded-btn border border-line bg-surface px-2 py-1 text-body outline-none"
            autofocus
            @click.stop
            @keyup.enter="commitRename(session.id)"
            @blur="commitRename(session.id)"
          />

          <template v-else>
            <span class="flex items-center gap-1.5">
              <span class="truncate">{{ session.title }}</span>
              <span v-if="session.id === props.currentId" class="ml-auto h-1.5 w-1.5 shrink-0 rounded-full grad-primary" aria-hidden="true" />
            </span>
            <span class="mt-0.5 block text-hint text-ink-muted">
              {{ session.messageCount }} 条消息
            </span>

            <!-- 触屏常显 / 指针悬停显示（.actions-reveal，见 main.css）。
                 注意：会话行整体是 <button>，内部不能再放可聚焦交互元素
                 （HTML 不允许嵌套交互内容，且 e2e 依赖本 nav 下的 button 计数），
                 故这里保持纯 span + click。 -->
            <span class="actions-reveal mt-1.5 md:mt-1">
              <span
                class="rounded-btn px-2 py-1.5 text-hint text-brand transition-colors active:bg-brand-soft md:px-0 md:py-0 md:active:bg-transparent"
                @click.stop="startRename(session)"
              >
                重命名
              </span>
              <span
                class="rounded-btn px-2 py-1.5 text-hint transition-colors active:bg-surface-2 md:px-0 md:py-0 md:active:bg-transparent"
                :class="confirmSince ? 'font-medium text-danger' : 'text-danger/70'"
                @click.stop="onRemove(session)"
              >
                {{ confirmSince ? '确认删除' : '删除' }}
              </span>
            </span>
          </template>
        </button>
      </section>
    </nav>
  </aside>
</template>
