<script setup lang="ts">
/* ============================================================
 * 命令面板（Ctrl / Cmd + K）
 *
 * 高频动作的一键盘触达：新建对话、搜索并跳转会话、切换主题、
 * 页面直达、退出登录。数据来自既有 store，无新增接口。
 * ============================================================ */

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useTheme } from '@/composer/useTheme'

const router = useRouter()
const user = useUserStore()
const chat = useChatStore()
const { toggle: toggleTheme, mode } = useTheme()

const open = ref(false)
const query = ref('')
const activeIdx = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

interface PaletteItem {
  key: string
  label: string
  hint?: string
  run: () => void
}

interface PaletteGroup {
  name: string
  items: PaletteItem[]
}

const groups = computed<PaletteGroup[]>(() => {
  const kw = query.value.trim().toLowerCase()

  const actions: PaletteItem[] = [
    {
      key: 'new-chat',
      label: '新建对话',
      hint: 'N',
      run: () => void router.push('/chat')
    },
    {
      key: 'theme',
      label: mode.value === 'dark' ? '切换到亮色模式' : '切换到暗色模式',
      run: toggleTheme
    },
    {
      key: 'logout',
      label: '退出登录',
      run: () => {
        void user.logout()
        void router.replace('/login')
      }
    }
  ]

  const pages: PaletteItem[] = [
    { key: 'page-chat', label: '对话', run: () => void router.push('/chat') },
    { key: 'page-knowledge', label: '知识探索', run: () => void router.push('/knowledge') },
    { key: 'page-health', label: '健康中心', run: () => void router.push('/health') },
    { key: 'page-profile', label: '个人中心', run: () => void router.push('/profile') }
  ]
  if (user.isAdmin) {
    pages.push({ key: 'page-admin', label: '管理端', run: () => void router.push('/admin') })
  }

  const sessions: PaletteItem[] = chat.sessions
    .filter((s) =>
      kw ? s.title.toLowerCase().includes(kw) : true
    )
    .slice(0, 8)
    .map((s) => ({
      key: `session-${s.id}`,
      label: s.title,
      hint: `${s.messageCount} 条消息`,
      run: () => void router.push(`/chat/${s.id}`)
    }))

  const filter = (items: PaletteItem[]) =>
    kw ? items.filter((i) => i.label.toLowerCase().includes(kw)) : items

  const result: PaletteGroup[] = [
    { name: '会话', items: sessions },
    { name: '页面', items: filter(pages) },
    { name: '动作', items: filter(actions) }
  ]
  return result.filter((g) => g.items.length > 0)
})

const flatItems = computed<PaletteItem[]>(() => groups.value.flatMap((g) => g.items))

watch([query, flatItems], () => {
  activeIdx.value = 0
})

async function show(): Promise<void> {
  open.value = true
  query.value = ''
  activeIdx.value = 0
  if (chat.sessions.length === 0) void chat.loadSessions()
  await nextTick()
  inputEl.value?.focus()
}

function close(): void {
  open.value = false
}

function exec(item: PaletteItem): void {
  close()
  item.run()
}

function onKeydown(e: KeyboardEvent): void {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (open.value) close()
    else void show()
    return
  }
  if (!open.value) return

  if (e.key === 'Escape') {
    e.preventDefault()
    close()
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIdx.value = (activeIdx.value + 1) % Math.max(flatItems.value.length, 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIdx.value =
      (activeIdx.value - 1 + flatItems.value.length) % Math.max(flatItems.value.length, 1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const item = flatItems.value[activeIdx.value]
    if (item) exec(item)
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <!-- 微交互④：出现/消失 scale + fade（裁决 §五.3 批准项） -->
    <Transition name="palette">
      <div v-if="open" class="fixed inset-0 z-[100]" @click.self="close">
        <div
          class="glass palette-panel mx-auto mt-[16vh] w-[min(92vw,560px)] rounded-card border border-line shadow-2xl"
          role="dialog"
          aria-label="命令面板"
        >
        <div class="flex items-center gap-2 border-b border-line px-4 py-3">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" class="text-ink-muted">
            <circle cx="11" cy="11" r="7" />
            <path d="m21 21-4.3-4.3" />
          </svg>
          <input
            ref="inputEl"
            v-model="query"
            type="text"
            placeholder="搜索会话、跳转页面、执行动作…"
            class="w-full bg-transparent text-body text-ink outline-none placeholder:text-ink-muted"
          />
          <kbd class="rounded border border-line px-1.5 py-0.5 text-hint text-ink-muted">Esc</kbd>
        </div>

        <div class="max-h-[46vh] overflow-y-auto p-2">
          <p v-if="flatItems.length === 0" class="py-8 text-center text-body text-ink-muted">
            没有匹配的结果
          </p>

          <section v-for="g in groups" :key="g.name" class="mb-1">
            <p class="px-3 pb-1 pt-2 text-hint text-ink-muted/70">{{ g.name }}</p>
            <button
              v-for="item in g.items"
              :key="item.key"
              type="button"
              class="flex w-full items-center gap-2 rounded-btn px-3 py-2 text-left text-body transition-colors"
              :class="
                flatItems[activeIdx]?.key === item.key
                  ? 'bg-brand-soft text-ink'
                  : 'text-ink hover:bg-surface-2'
              "
              :data-active="flatItems[activeIdx]?.key === item.key"
              @mouseenter="
                activeIdx = flatItems.findIndex((i) => i.key === item.key)
              "
              @click="exec(item)"
            >
              <span class="truncate">{{ item.label }}</span>
              <span v-if="item.hint" class="ml-auto shrink-0 text-hint text-ink-muted">
                {{ item.hint }}
              </span>
            </button>
          </section>
        </div>

        <div class="flex items-center gap-3 border-t border-line px-4 py-2 text-hint text-ink-muted">
          <span>↑↓ 选择</span>
          <span>Enter 确认</span>
          <span class="ml-auto">Ctrl / ⌘ + K</span>
        </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.palette-enter-active,
.palette-leave-active {
  transition: opacity 0.16s ease;
}
.palette-enter-active .palette-panel,
.palette-leave-active .palette-panel {
  transition:
    transform 0.16s ease,
    opacity 0.16s ease;
}
.palette-enter-from,
.palette-leave-to {
  opacity: 0;
}
.palette-enter-from .palette-panel,
.palette-leave-to .palette-panel {
  transform: scale(0.96) translateY(-8px);
  opacity: 0;
}
</style>
