<script setup lang="ts">
/* ============================================================
 * 用户端布局 v2 —— 毛玻璃顶栏 + 品牌脉搏
 *
 * 顶栏脉搏线是全站的「系统状态灯」：流式生成期间加速（live）。
 * e2e 契约：顶栏文本含「健康科普助手」、退出按钮文案「退出」。
 *
 * 移动端（<768px）形态：
 *   顶栏精简为「品牌脉搏 + 当前页标题 + 主题切换」，
 *   四个一级入口下沉到底部标签栏（MobileTabBar）——
 *   拇指可达、不再与顶栏抢 375px 的横向空间。
 *   账户相关（改名/改密/退出）由「我的」标签进入个人中心完成。
 *   ⚠️ 桌面（≥768px）形态保持原样，e2e 断言（1280 视口）不受影响。
 * ============================================================ */

import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { usePulse } from '@/composer/usePulse'
import PulseMark from '@/components/PulseMark.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import CommandPalette from '@/components/CommandPalette.vue'
import MobileTabBar from '@/components/MobileTabBar.vue'

const user = useUserStore()
const route = useRoute()
const router = useRouter()
const pulse = usePulse()

/** 移动端顶栏标题：取路由 meta.title，兜底品牌名 */
const pageTitle = computed<string>(() => (route.meta.title as string | undefined) ?? '健康科普助手')

async function onLogout(): Promise<void> {
  await user.logout()
  void router.replace('/login')
}

/** 命令面板由 CommandPalette 的全局快捷键驱动，这里派发同一事件复用入口 */
function openPalette(): void {
  window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
}
</script>

<template>
  <div class="flex h-full flex-col">
    <header class="glass sticky top-0 z-30 flex h-14 shrink-0 items-center justify-between border-b border-line px-4">
      <nav class="flex items-center gap-4">
        <span class="flex items-center gap-2">
          <PulseMark :live="pulse.live.value" :width="44" />
          <span class="hidden text-body font-medium text-ink md:inline">健康科普助手</span>
          <!-- 移动端：用当前页标题替代品牌名，省出横向空间 -->
          <span class="text-body font-medium text-ink md:hidden">{{ pageTitle }}</span>
        </span>

        <!-- 一级导航：仅桌面显示，移动端交由底部标签栏 -->
        <router-link
          to="/chat"
          class="hidden text-body text-ink-muted transition-colors hover:text-brand md:inline-block"
        >
          对话
        </router-link>
        <router-link
          to="/knowledge"
          class="hidden text-body text-ink-muted transition-colors hover:text-brand md:inline-block"
        >
          健康科普
        </router-link>
        <router-link
          to="/health"
          class="hidden text-body text-ink-muted transition-colors hover:text-brand md:inline-block"
        >
          个人健康科普
        </router-link>
        <router-link
          v-if="user.isAdmin"
          to="/admin"
          class="hidden text-body text-ink-muted transition-colors hover:text-brand md:inline-block"
        >
          管理端
        </router-link>
      </nav>

      <div class="flex items-center gap-3">
        <!-- 命令面板入口：移动端无物理键盘，隐藏 -->
        <button
          type="button"
          class="hidden items-center gap-1.5 rounded-btn border border-line px-2 py-1 text-hint text-ink-muted transition-colors hover:border-brand hover:text-brand sm:flex"
          aria-label="打开命令面板"
          @click="openPalette"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="11" cy="11" r="7" />
            <path d="m21 21-4.3-4.3" />
          </svg>
          <kbd>Ctrl K</kbd>
        </button>

        <ThemeToggle />

        <!-- 账户入口：移动端收进「我的」标签页 -->
        <router-link
          to="/profile"
          class="hidden text-body text-ink-muted transition-colors hover:text-brand md:inline-block"
        >
          {{ user.displayName || '个人中心' }}
        </router-link>
        <button
          type="button"
          class="hidden text-body text-ink-muted transition-colors hover:text-danger md:inline-block"
          @click="onLogout"
        >
          退出
        </button>
      </div>
    </header>

    <!-- 中间区承接滚动（2026-10-16 修正）：
         此前 main 没有 overflow，内容直接溢出到 main 盒子之外 —— 后果有两个，
         且都只在「内容比一屏长」时暴露，所以首屏截图看不出来：
           ① 溢出的内容被后绘制的标签栏盖住（贴在页尾的按钮点不到）；
           ② 溢出把文档撑高，document 成为滚动容器，于是**滚动时标签栏被一起带走**。
         让 main 成为唯一滚动容器，顶栏与标签栏即恒定，内容止于标签栏上沿。 -->
    <main class="min-h-0 flex-1 overflow-y-auto">
      <!-- 页面级过渡：轻淡入上移，为切换提供方位感（reduced-motion 下自动禁用） -->
      <router-view v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" v-if="Component" />
        </Transition>
      </router-view>
    </main>

    <!-- 移动端唯一导航入口（flex 兄弟节点：自然占位。标签栏恒定不动的成立前提是
         main 已承接滚动，文档本身不再滚动） -->
    <MobileTabBar />

    <CommandPalette />
  </div>
</template>

<style scoped>
.page-enter-active,
.page-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}
.page-enter-from {
  opacity: 0;
  transform: translateY(5px);
}
.page-leave-to {
  opacity: 0;
}
</style>
