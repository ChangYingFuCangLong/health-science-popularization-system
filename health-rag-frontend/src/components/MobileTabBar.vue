<script setup lang="ts">
/* ============================================================
 * 移动端底部标签栏（<768px 唯一导航入口）
 *
 * 为什么是底部标签栏而不是汉堡抽屉：
 *   用户端只有 4 个一级入口，正好填满标签栏；拇指自然可达区在屏幕下半部，
 *   顶栏单行塞入 4 个文字链接在 375px 下必然溢出（改造前的实际问题）。
 *
 * 与全站「生命体征 · Vital」体系的衔接：
 *   激活项用品牌青（检索色）点亮，顶部 1 根 2px 指示条 —— 与脉搏线的
 *   「系统在工作」语义同源；动效克制在 200ms 的色彩与位移过渡。
 *
 * 无障碍：nav[aria-label="主导航"] + aria-current="page"；
 *   单元高度 54px、宽度 1/4 屏，远超 44×44px 触摸目标基准。
 * ============================================================ */

import { useRoute } from 'vue-router'

const route = useRoute()

interface Tab {
  to: string
  /** 路径前缀匹配：/chat/12 也应点亮「对话」 */
  match: string
  label: string
  /** 24×24 viewBox 下的 path d 列表 */
  paths: string[]
}

const TABS: Tab[] = [
  {
    to: '/chat',
    match: '/chat',
    label: '智能健康科普',
    paths: [
      'M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z'
    ]
  },
  {
    to: '/health',
    match: '/health',
    label: '个人健康科普',
    paths: ['M22 12h-4l-3 9L9 3l-3 9H2']
  },
  {
    to: '/profile',
    match: '/profile',
    label: '设置',
    paths: ['M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2', 'M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z']
  }
]

function isActive(match: string): boolean {
  return route.path === match || route.path.startsWith(`${match}/`)
}
</script>

<template>
  <nav
    class="tabbar-safe glass shrink-0 border-t border-line md:hidden"
    aria-label="主导航"
  >
    <ul class="flex" :style="{ height: 'var(--size-tabbar)' }">
      <li v-for="tab in TABS" :key="tab.to" class="flex-1">
        <router-link
          :to="tab.to"
          class="tab-item relative flex h-full flex-col items-center justify-center gap-1"
          :class="isActive(tab.match) ? 'tab-item--on' : 'tab-item--off'"
          :aria-current="isActive(tab.match) ? 'page' : undefined"
        >
          <!-- 激活指示条：与脉搏线同源的「在工作」语义，克制到 2px -->
          <span class="tab-bar" aria-hidden="true" />
          <svg
            class="tab-icon"
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path v-for="(d, i) in tab.paths" :key="i" :d="d" />
          </svg>
          <span class="text-[10px] leading-none whitespace-nowrap">{{ tab.label }}</span>
        </router-link>
      </li>
    </ul>
  </nav>
</template>

<style scoped>
.tab-item {
  transition: color 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

.tab-item--on {
  color: var(--c-brand);
}

.tab-item--off {
  color: var(--c-ink-muted);
}

/* 指示条：默认缩为 0，激活时横向展开 */
.tab-bar {
  position: absolute;
  top: 0;
  width: 22px;
  height: 2px;
  border-radius: 0 0 2px 2px;
  background: var(--c-brand);
  transform: scaleX(0);
  transition: transform 0.24s cubic-bezier(0.16, 1, 0.3, 1);
}

.tab-item--on .tab-bar {
  transform: scaleX(1);
}

.tab-icon {
  transition: transform 0.24s cubic-bezier(0.16, 1, 0.3, 1);
}

.tab-item--on .tab-icon {
  transform: translateY(-1px) scale(1.06);
}

/* 触屏按下反馈（替代在触屏上会粘住的 hover） */
.tab-item:active .tab-icon {
  transform: scale(0.94);
}

@media (prefers-reduced-motion: reduce) {
  .tab-bar,
  .tab-icon {
    transition: none;
  }
}
</style>
