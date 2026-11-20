/* ============================================================
 * 上传文件类型白名单 —— 前端唯一出处
 *
 * ⚠️ 真值在后端，本文件只是镜像：
 *   配置：health-rag-backend/src/main/resources/application.yml
 *        health.rag.upload-allowed-types（默认 txt,md,html,htm,pdf,docx）
 *        可由环境变量 KB_UPLOAD_TYPES 覆盖
 *   校验：DocumentPipelineService#validateExtension（唯一校验点，只看扩展名）
 *   上限：health.rag.upload-max-size-mb（默认 20 MB，KB_UPLOAD_MAX_MB）
 *
 * 后端改白名单时，**本文件必须同步**，否则前端会拦掉后端本可处理的格式
 * （历史问题：曾出现 4 套互不相同的白名单，用户端只放行 txt/json，
 *  导致 PDF/DOCX 在客户端就被挡，请求根本没发出）。
 *
 * 三个上传入口统一引用本文件，禁止各自再写字面量：
 *   1. 管理端 · 语料上传          views/admin/DocumentView.vue
 *   2. 用户端 · 我的资料库        components/KbUploadZone.vue（经 api/userKb.ts）
 *   3. 用户端 · 体检报告识读      views/user/HealthDetailView.vue
 * ============================================================ */

/** 与后端 upload-allowed-types 保持一致（顺序仅影响提示文案） */
export const UPLOAD_ALLOWED_TYPES = ['txt', 'md', 'html', 'htm', 'pdf', 'docx'] as const

export type UploadAllowedType = (typeof UPLOAD_ALLOWED_TYPES)[number]

/** 给 <input accept> / el-upload accept 用 */
export const UPLOAD_ACCEPT = UPLOAD_ALLOWED_TYPES.map((t) => `.${t}`).join(',')

/** 单文件大小上限（与后端 upload-max-size-mb 一致） */
export const UPLOAD_MAX_SIZE = 20 * 1024 * 1024

/** 提示文案用：TXT / MD / HTML / HTM / PDF / DOCX */
export const UPLOAD_TYPES_LABEL = UPLOAD_ALLOWED_TYPES.map((t) => t.toUpperCase()).join(' / ')

/** 取小写扩展名（无扩展名返回空串） */
export function extOf(filename: string): string {
  const i = filename.lastIndexOf('.')
  return i >= 0 ? filename.slice(i + 1).toLowerCase() : ''
}

/**
 * 上传前本地预校验：把明显不合格的文件挡在请求之前。
 * 服务端仍会再校验（客户端校验只是体验优化，不是安全边界）。
 * 返回 null 表示通过，否则返回中文错误文案。
 */
export function validateUploadFile(file: File): string | null {
  const ext = extOf(file.name)
  if (!(UPLOAD_ALLOWED_TYPES as readonly string[]).includes(ext)) {
    return `不支持的文件类型：${ext || '未知'}（支持 ${UPLOAD_TYPES_LABEL}）`
  }
  if (file.size === 0) return '文件是空的'
  if (file.size > UPLOAD_MAX_SIZE) return '文件超过 20 MB 上限'
  return null
}
