/* 全局提示的唯一入口（前端设计.md §2）。
 * 实现在 composer/useToast.ts —— 自建 Tailwind 方案，不引 Element Plus，
 * 否则 EP 会被打进主 chunk，管理端懒加载收益归零。 */
export { showMessage, dismissToast, clearToasts, toasts } from '@/composer/useToast'
export type { ToastType, Toast } from '@/composer/useToast'
