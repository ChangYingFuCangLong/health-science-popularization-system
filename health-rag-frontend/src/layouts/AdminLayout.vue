<script setup lang="ts">
/* ============================================================
 * 管理端布局
 * 依据：前端设计.md §7.1 —— 管理端样式以 Element Plus 为主
 * （与用户端的 Tailwind 令牌体系隔离，避免两套样式互相污染；
 *  响应式外壳层允许使用 Tailwind 工具类，与工程现状一致）
 *
 * 移动端（<768px）形态：
 *   改造前 el-aside 是固定 200px，手机上恒定占掉 53% 屏宽且无任何折叠方式，
 *   表格与图表被挤到无法阅读 —— 本文件的主修复点。
 *   现形态：侧栏隐藏 → 顶栏汉堡按钮唤出 el-drawer 抽屉菜单。
 *   ⚠️ 桌面（≥768px）保持 200px 常驻侧栏不变，admin.mjs（1440 视口）断言不受影响。
 * ============================================================ */

import { computed, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const MENUS = [
  { path: '/admin/dashboard', label: '数据看板' },
  { path: '/admin/documents', label: '语料管理' },
  { path: '/admin/logs', label: '问答日志' },
  { path: '/admin/feedbacks', label: '反馈管理' },
  { path: '/admin/users', label: '用户管理' },
  { path: '/admin/eval/dataset', label: '评测集管理' },
  { path: '/admin/eval/results', label: '实验结果' }
]

/** 分块查看是子页面，高亮落在「语料管理」上 */
const activeMenu = computed(() => {
  if (route.path.includes('/admin/documents')) return '/admin/documents'
  if (route.path.startsWith('/admin/eval')) return route.path
  return route.path
})

/** 移动端顶栏标题 */
const currentLabel = computed<string>(() => {
  const hit = MENUS.find((m) => m.path === activeMenu.value)
  return hit?.label ?? '管理端'
})

/* ---------------- 移动端抽屉菜单 ---------------- */
const drawerOpen = ref(false)

function onDrawerSelect(path: string): void {
  drawerOpen.value = false
  onSelect(path)
}

/** 视口回到桌面宽度时收起抽屉，避免与常驻侧栏同时存在 */
function onMqChange(e: MediaQueryListEvent): void {
  if (!e.matches) drawerOpen.value = false
}

let mqMobile: MediaQueryList | null = null
if (typeof window !== 'undefined') {
  mqMobile = window.matchMedia('(max-width: 767px)')
  mqMobile.addEventListener('change', onMqChange)
}
onUnmounted(() => mqMobile?.removeEventListener('change', onMqChange))

function onSelect(path: string): void {
  void router.push(path)
}

function backToUser(): void {
  drawerOpen.value = false
  void router.push('/chat')
}
</script>

<template>
  <div class="admin-shell flex h-full flex-col">
    <!-- 移动端顶栏：汉堡唤出抽屉菜单 + 当前页标题 + 返回用户端 -->
    <header class="flex h-14 shrink-0 items-center gap-1 border-b border-gray-200 bg-white px-1 md:hidden">
      <button
        type="button"
        class="flex h-11 w-11 shrink-0 items-center justify-center rounded text-gray-600 active:bg-gray-100"
        aria-label="打开管理菜单"
        @click="drawerOpen = true"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
          <path d="M3 6h18M3 12h18M3 18h18" />
        </svg>
      </button>
      <span class="min-w-0 flex-1 truncate text-base font-medium text-gray-800">{{ currentLabel }}</span>
      <button
        type="button"
        class="h-11 shrink-0 rounded px-3 text-sm text-gray-500 active:bg-gray-100"
        @click="backToUser"
      >
        返回用户端
      </button>
    </header>

    <el-container class="min-h-0 flex-1">
      <!-- 桌面常驻侧栏（<768px 由下方 CSS 隐藏） -->
      <el-aside width="200px" class="admin-aside border-r border-gray-200 bg-white">
        <div class="py-4 text-center text-base font-medium text-gray-800">管理端</div>
        <el-menu :default-active="activeMenu" @select="onSelect">
          <el-menu-item v-for="m in MENUS" :key="m.path" :index="m.path">
            {{ m.label }}
          </el-menu-item>
        </el-menu>
        <div class="p-3">
          <el-button plain style="width: 100%" @click="backToUser">返回用户端</el-button>
        </div>
      </el-aside>

      <el-main class="admin-main bg-gray-50">
        <router-view v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" v-if="Component" />
          </Transition>
        </router-view>
      </el-main>
    </el-container>

    <!-- 移动端抽屉菜单（方向从左，与桌面侧栏位置一致，降低方位感落差） -->
    <el-drawer v-model="drawerOpen" direction="ltr" size="240px" class="admin-drawer">
      <template #header>
        <span class="text-base font-medium text-gray-800">管理端</span>
      </template>

      <el-menu :default-active="activeMenu" @select="onDrawerSelect">
        <el-menu-item v-for="m in MENUS" :key="m.path" :index="m.path">
          {{ m.label }}
        </el-menu-item>
      </el-menu>

      <div class="p-3">
        <el-button plain style="width: 100%" @click="backToUser">返回用户端</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 移动端：隐藏固定宽度侧栏，改为抽屉；压缩 el-main 内边距给表格让出宽度 */
@media (max-width: 767px) {
  .admin-aside {
    display: none;
  }
  .admin-main {
    padding: 12px;
  }
}

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
