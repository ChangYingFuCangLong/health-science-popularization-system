/* ============================================================
 * 通用类型 —— 与 docs/接口文档.md 的契约对齐
 * 契约约定：不使用 any，未知结构用 unknown + 类型守卫
 * ============================================================ */

/** 后端统一响应结构 */
export interface R<T = unknown> {
  code: number
  message: string
  data: T
}

/** 分页响应结构 */
export interface PageResult<T> {
  total: number
  pages: number
  current: number
  size: number
  records: T[]
}

/* ---------------- 认证 / 用户 ---------------- */

export type UserRole = 'USER' | 'ADMIN'

export interface UserInfo {
  id: number
  username: string
  nickname: string
  email: string | null
  avatar: string | null
  role: UserRole
  createTime: string
}

export interface LoginResult {
  token: string
  expiresIn: number
  userInfo: UserInfo
}

/* ---------------- 问答 ---------------- */

/** 引用片段（02 v4.0 §4.3 reference.chunks[]） */
export interface ReferenceChunk {
  chunkId: number
  /** 来源文档 ID。缺它则"可溯源"无法闭环：前端点不开原文（ADR-019） */
  docId: number
  /** 来源名：权威库为文档标题；个人库为资料名；文档解惑为上传文件名 */
  doc: string
  section: string
  digest: string
  score: number
  /**
   * 来源归属（02 v4.0 §4.3）：
   *   global   权威知识库（机构语料）
   *   personal 用户个人资料库
   *   file     文档解惑的会话文件（doc 字段即文件名）
   *
   * ⚠️ 可选：本功能上线**前**落库的历史消息里没有该字段，
   * 渲染时必须按 `global` 兜底，否则老会话会整片变成「我的资料」。
   */
  scope?: 'global' | 'personal' | 'file'
}

export type RetrieveMode = 'naive' | 'hybrid' | 'hybrid_rerank' | 'none'

/** 消息完成状态：0 流中断（内容已保留），1 已完成（ADR-015） */
export type FinishStatus = 0 | 1

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  references: ReferenceChunk[]
  retrieveMode: RetrieveMode | null
  latencyMs: number | null
  /** 首字延迟（毫秒），由后端在推送首个 delta 时打点（ADR-017） */
  firstTokenMs: number | null
  finishStatus: FinishStatus | null
  isRejected: 0 | 1 | null
  createTime: string
  /**
   * 答案来源（对照解答，02 v4.0 §4.3）：global=系统版 / fused=融合个人版。
   * 仅个人解惑且触发对照时非空；历史消息 null。
   */
  answerSource?: 'global' | 'fused' | null
  /** 对照解答组 ID（02 v4.0 §4.3）：同组两条消息整组重答 */
  conflictGroupId?: number | null
}

/** 会话类型（02 v4.0 §1.5 三值矩阵）：normal 通用解惑 / health 个人解惑 / file 文档解惑 */
export type SessionKind = 'normal' | 'health' | 'file'

export interface ChatSession {
  id: number
  title: string
  messageCount: number
  createTime: string
  updateTime: string
  /**
   * 会话类型（02 v4.0 §1.5）：
   *   normal 通用解惑（AI解惑页，GET /chat/sessions 恒只返回此类）
   *   health 个人解惑（个人健康科普模块，列表走 #58）
   *   file   文档解惑（健康科普页，列表走 #70）
   *
   * ⚠️ 可选：三值扩展**前**落库的会话没有该字段，前端一律按 `normal` 兜底。
   */
  kind?: SessionKind
}

/* ---------------- SSE 事件载荷 ---------------- */

/** event: reference */
export interface ReferencePayload {
  chunks: ReferenceChunk[]
  /**
   * 检索前改写后的查询词（02-接口文档 §4.6 ★，rewrite-enabled=false 时等于原问题）。
   * V1 后端不传该字段——前端按缺省处理，仅在存在时于阶段时间线展示。
   */
  rewrittenQuery?: string
}

/**
 * event: stage（02 v4.0 §4.3，🟢）。
 * 检索/生成的显式阶段信号；未收到时前端从 reference 事件推断，行为不变。
 * 兼容说明：早期联调用 `step` 字段名，v4.0 定稿为 `stage`——两者都收。
 */
export interface StagePayload {
  stage?: 'retrieve_start' | 'retrieve_done' | 'generate_start'
  step?: 'retrieve_start' | 'retrieve_done' | 'generate_start'
  /** retrieve_done 时携带改写后的检索词 */
  rewrittenQuery?: string
}

/** event: delta */
export interface DeltaPayload {
  content: string
  /** 仅对照解答模式：标识增量属于哪个答案（02 v4.0 §4.3） */
  answerId?: number
}

/** event: answer_start（对照解答 🟡B-27）：串行双答案，先系统版后融合版 */
export interface AnswerStartPayload {
  answerId: number
  source: 'global' | 'fused'
  label: string
}

/** event: answer_end（对照解答 🟡B-27） */
export interface AnswerEndPayload {
  answerId: number
  messageId: number | null
}

/** event: done */
export interface DonePayload {
  /** M4 落库后才填入；M1~M3 阶段为 null（后端 ChatController 明示） */
  messageId: number | null
  /** 同上；会话由后端隐式新建时才有值 */
  sessionId: number | null
  latencyMs: number
  firstTokenMs: number
  finishStatus: FinishStatus
  /** 完整回答全文。前端合帧节流需要它来校正增量（ADR-016）——不要精简掉 */
  content: string
  /**
   * 是否真的注入了个人健康信息（02 v4.0 §4.3，原 portraitUsed 更名）。
   * 仅当会话为 `health` 且四层健康上下文非空时为 true——
   * 前端必须据此显示「已参考你的健康画像」，而不是按会话类型想当然地显示，
   * 否则用户清空画像后界面仍在宣称"已参考"，那是产品在撒谎。
   * 可选字段：旧后端不返回，前端按 false 处理（不显示该提示）。
   */
  healthUsed?: boolean
  /**
   * 健康矛盾提醒（🟡B-28）：用户陈述值与档案记录值冲突时由后端剥离标记生成。
   * 前端展示确认卡，用户确认后写入档案（预填）。
   */
  healthReminder?: {
    field: string
    recordedValue: string
    statedValue: string
  } | null
  /** 健康建议提示语（🟡B-28，可选，展示在回答之后） */
  healthSuggestion?: string | null
  /** 对照解答的答案条数（🟡B-27；1 或缺省 = 普通单答案） */
  answerCount?: number
}

/** event: error */
export interface ErrorPayload {
  code: number
  message: string
}

/**
 * SSE 事件处理器（02 v4.0 §4.3 全集）。
 * 只声明协议里实际会推的事件，避免把拼写错误静默吞掉。
 */
export interface SSEHandlers {
  reference?: (payload: ReferencePayload) => void
  stage?: (payload: StagePayload) => void
  delta?: (payload: DeltaPayload) => void
  answer_start?: (payload: AnswerStartPayload) => void
  answer_end?: (payload: AnswerEndPayload) => void
  done?: (payload: DonePayload) => void
  error?: (payload: ErrorPayload) => void
}

/* ---------------- 流式请求 ---------------- */

export interface StreamChatRequest {
  sessionId?: number | null
  question: string
  /**
   * 仅用于「重新解答」：指定要被替换的助手消息 ID（02 v4.0 §4.3）。
   * 若指向对照解答组内任一条，后端整组重跑（前端无感知）。
   */
  retryFromMessageId?: number
  /**
   * 检索策略（02 v4.0 §4.3）：none / naive / hybrid / hybrid_rerank。
   * 缺省取服务端默认（hybrid）。前端暂不提供切换 UI，仅对齐契约。
   *
   * 注意：v4.0 契约**没有 kbScope 参数**——检索范围由会话 kind 决定
   * （normal=基础库 / health=基础库+个人资料库 / file=仅会话文件集），
   * 旧的三态切换开关已随 07 号 v2 裁决移除。
   */
  retrieveMode?: RetrieveMode
}
