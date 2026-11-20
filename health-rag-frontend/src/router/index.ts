import { createRouter, createWebHistory } from 'vue-router'
import { setupGuard } from './guard'

declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录页面 */
    public?: boolean
    /** 页面标题 */
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(),

  // 全部懒加载（前端设计.md §3.1）：
  // 工程同时存在 Tailwind / Element Plus / ECharts / highlight.js 四份重依赖，
  // 不懒加载时用户端首屏要下载整个管理端的依赖（EP ≈ 900KB + ECharts ≈ 1MB）。
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/user/LoginView.vue'),
      meta: { public: true, title: '登录' }
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/user/RegisterView.vue'),
      meta: { public: true, title: '注册' }
    },

    {
      path: '/',
      component: () => import('@/layouts/UserLayout.vue'),
      children: [
        { path: '', redirect: '/chat' },
        {
          path: 'chat/:sessionId?',
          name: 'chat',
          component: () => import('@/views/user/ChatView.vue'),
          meta: { title: '对话' }
        },
        {
          // 文章详情为面板形态，不占独立路由页（ADR-019）
          path: 'knowledge/:id?',
          name: 'knowledge',
          component: () => import('@/views/user/KnowledgeView.vue'),
          meta: { title: '健康科普' }
        },
        {
          // 文档解惑工作区（02 v4.0 §五 #69-75）：左文件集右对话
          path: 'knowledge/qa/:sessionId',
          name: 'knowledge-qa',
          component: () => import('@/views/user/QaChatView.vue'),
          meta: { title: '文档解惑' }
        },
        {
          // 个人健康科普 · 概览（个人解惑区 + 健康摘要，11 号 §〇）
          path: 'health',
          name: 'health',
          component: () => import('@/views/user/HealthOverview.vue'),
          meta: { title: '个人健康科普' }
        },
        {
          path: 'health/detail',
          name: 'health-detail',
          component: () => import('@/views/user/HealthDetailView.vue'),
          meta: { title: '健康详情' }
        },
        {
          path: 'health/kb',
          name: 'health-kb',
          component: () => import('@/views/user/KbPage.vue'),
          meta: { title: '我的资料库' }
        },
        {
          // 个人解惑对话（复用对话组件，kind='health'）
          path: 'health/chat/:sessionId',
          name: 'health-chat',
          component: () => import('@/views/user/HealthChatView.vue'),
          meta: { title: '个人解惑' }
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/user/ProfileView.vue'),
          meta: { title: '个人中心' }
        }
      ]
    },

    {
      path: '/admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      children: [
        { path: '', redirect: '/admin/dashboard' },
        {
          path: 'dashboard',
          component: () => import('@/views/admin/DashboardView.vue'),
          meta: { title: '数据看板' }
        },
        {
          path: 'documents',
          component: () => import('@/views/admin/DocumentView.vue'),
          meta: { title: '语料管理' }
        },
        {
          path: 'documents/:id/chunks',
          component: () => import('@/views/admin/ChunkView.vue'),
          meta: { title: '分块查看' }
        },
        {
          path: 'logs',
          component: () => import('@/views/admin/ChatLogView.vue'),
          meta: { title: '问答日志' }
        },
        {
          path: 'feedbacks',
          component: () => import('@/views/admin/FeedbackView.vue'),
          meta: { title: '反馈管理' }
        },
        {
          path: 'users',
          component: () => import('@/views/admin/UserView.vue'),
          meta: { title: '用户管理' }
        },
        {
          path: 'eval/dataset',
          component: () => import('@/views/admin/EvalDatasetView.vue'),
          meta: { title: '评测集管理' }
        },
        {
          path: 'eval/results',
          component: () => import('@/views/admin/EvalResultView.vue'),
          meta: { title: '实验结果' }
        }
      ]
    },

    { path: '/:pathMatch(.*)*', redirect: '/chat' }
  ]
})

setupGuard(router)

export default router
