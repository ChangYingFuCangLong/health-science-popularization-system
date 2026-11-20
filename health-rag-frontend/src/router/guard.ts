/* ============================================================
 * 全局路由守卫（前端设计.md §3.2）
 *
 * ⚠️ 边界：前端守卫只做体验优化，真正的权限校验在后端
 * （/api/admin/** 要求 ADMIN 角色）。前端隐藏菜单不等于安全。
 *
 * v1.1 修正：原第 4 步"等待 /api/auth/me 返回再放行"会让每次刷新
 * 都白屏等一个网络往返。改为【放行 + 后台补齐】，页面立刻渲染；
 * 代价是管理员刷新管理端时入口延迟约 100~300ms 出现，明显小于"每次都等"。
 * ============================================================ */

import type { Router } from 'vue-router'
import { useUserStore } from '@/stores/user'

export function setupGuard(router: Router): void {
  router.beforeEach((to) => {
    const user = useUserStore()

    // 1. 免登录页：已登录则回主页
    if (to.meta.public) {
      return user.isLoggedIn ? { path: '/' } : true
    }

    // 2. 无 token：记录回跳地址，登录成功后回跳
    if (!user.isLoggedIn) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }

    // 3. 有 token 但 userInfo 为空（刷新页面后）：
    //    不阻塞，后台补齐；失败（401）由 authError 统一踢回登录
    //
    //    但补齐之后必须【回头再判一次权限】：刷新 /admin 时角色还是未知的，
    //    第 4 步会被跳过而放行，页面会停在管理端并显示后端的 403，
    //    且此后不会再被拦下（实测：URL 停在 /admin/dashboard）。
    //    因此这里在 fetchMe 结束后补一次拦截。
    if (user.userInfo == null) {
      void user
        .fetchMe()
        .then(() => {
          // 用【守卫的目标路由 to】判断，不能用 router.currentRoute：
          // 初始导航的 beforeEach 执行时，currentRoute 还是起始位置（'/'），
          // 拿它判断会漏掉所有刷新进入 /admin 的情况。
          if (to.path.startsWith('/admin') && !user.isAdmin) {
            void router.replace('/')
          }
        })
        .catch(() => user.clear())
    }

    // 4. 已知角色且目标为 /admin：非管理员拦截
    //    （角色未知时放行，由后端校验兜底）
    if (to.path.startsWith('/admin') && user.userInfo != null && !user.isAdmin) {
      return { path: '/' }
    }

    return true
  })
}
