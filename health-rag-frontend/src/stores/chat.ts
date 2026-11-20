/* ============================================================
 * 对话状态（前端设计.md §4）—— 全工程最核心的 store
 *
 * 关键设计：
 *   1. URL 是会话状态的唯一真相（§4.1）：本 store 不碰 router，
 *      切换会话由视图层 router.push 触发，再调用 loadSession。
 *   2. 流式渲染合帧节流（§5.3 / ADR-016）：delta 逐 token 推送，
 *      300 字回答约 300~600 次事件，逐次渲染是 O(n²)，必然掉帧。
 *   3. done 事件回传全文 content，用于校正（兜住标签页隐藏时 rAF 暂停的堆积）。
 *   4. 流中断保留已生成内容并置 finishStatus = 0（ADR-015）。
 * ============================================================ */

import { ref } from 'vue'
import { defineStore } from 'pinia'
import type {
  AnswerEndPayload,
  AnswerStartPayload,
  ChatMessage,
  ChatSession,
  DonePayload,
  SSEHandlers
} from '@/api/types'
import * as chatApi from '@/api/chat'
import { streamChat } from '@/api/sseClient'
import { createRafThrottle } from '@/utils/rafThrottle'
import { showMessage } from '@/utils/message'

const MESSAGES_PAGE_SIZE = 50

/** 拒答文案（§11.3：必须引导就医，集中维护，不散落在组件里） */
export const REJECTED_ANSWER =
  '知识库中暂无相关内容。建议调整提问方式，或前往正规医疗机构咨询专业医生。'

/**
 * 危机干预关键词（§11.2）。
 * 原则：只提示、不拦截、不隐藏用户输入、不静默改写——
 * 产品可以提示求助渠道，但不能替用户决定"这个问题不该问"。
 */
const CRISIS_KEYWORDS = ['自杀', '轻生', '不想活', '想死', '结束生命', '自残', '自伤']

export function hitsCrisisRule(text: string): boolean {
  return CRISIS_KEYWORDS.some((k) => text.includes(k))
}

/**
 * RAG 过程可视化（v2 创新点 1）：
 * 把「检索增强」黑盒显性化的状态机——
 *   understand 理解问题 → retrieve 检索知识库 → generate 生成回答 → done 完成
 * SSE 协议未变：reference 事件到达即视为检索完成，首个 delta 即进入生成，
 * 前端不猜、不造事件，全部由真实协议事件驱动。
 */
export type MessageStage = 'understand' | 'retrieve' | 'generate' | 'done'

/** 本地消息：在契约之上补充流式过程的瞬时态（不入库） */
export interface LocalMessage extends ChatMessage {
  /** 生成中 */
  streaming?: boolean
  /** 当前所处 RAG 阶段（仅本次会话内流式产生的消息有值） */
  stage?: MessageStage
  /** 检索耗时（reference 到达时打点，毫秒） */
  retrieveMs?: number | null
  /** 首字到达耗时（客户端测量） */
  firstDeltaMs?: number | null
  /** 改写后的检索词（reference.rewrittenQuery / stage.rewrittenQuery） */
  rewrittenQuery?: string | null
  /** 检索是否已完成（stage.retrieve_done 或 reference 事件置位） */
  retrieved?: boolean
  /**
   * 是否真的注入了个人健康信息（02 v4.0 done.healthUsed，原 portraitUsed 更名）。
   * 由服务端确认，不用会话类型推断——档案清空后仍宣称"已参考"就是撒谎。
   * undefined = 后端未返回该字段（旧后端）或历史消息。
   */
  healthUsed?: boolean
  /** 健康矛盾提醒（done.healthReminder）：确认后写档案 */
  healthReminder?: { field: string; recordedValue: string; statedValue: string } | null
  /** 健康建议提示语（done.healthSuggestion） */
  healthSuggestion?: string | null
  /**
   * 对照解答的分段（🟡B-27）：answer_start/answer_end 把一次回答切成
   * 系统版（global）与融合个人版（fused）两段，逐段渲染为对比卡。
   * 仅个人解惑触发；普通单答案回答此字段为空、走 content 单段渲染。
   */
  segments?: MessageSegment[]
}

/** 对照解答的单个答案段 */
export interface MessageSegment {
  answerId: number
  source: 'global' | 'fused'
  label: string
  content: string
  /** answer_end 置位 */
  ended?: boolean
}

let localSeq = 0

function localUserMessage(content: string): LocalMessage {
  return {
    id: --localSeq,
    role: 'user',
    content,
    references: [],
    retrieveMode: 'none',
    latencyMs: 0,
    firstTokenMs: 0,
    finishStatus: 1,
    isRejected: 0,
    createTime: new Date().toISOString()
  }
}

function assistantPlaceholder(): LocalMessage {
  return {
    id: --localSeq,
    role: 'assistant',
    content: '',
    references: [],
    retrieveMode: 'none',
    latencyMs: 0,
    firstTokenMs: 0,
    finishStatus: 1,
    isRejected: 0,
    createTime: new Date().toISOString(),
    streaming: true,
    stage: 'understand'
  }
}

export const useChatStore = defineStore('chat', () => {
  /* ---------------- state ---------------- */
  const sessions = ref<ChatSession[]>([])
  const currentSessionId = ref<number | null>(null)
  const messages = ref<LocalMessage[]>([])
  const isStreaming = ref(false)
  /** 危机干预提示是否可见（§11.2） */
  const crisisVisible = ref(false)
  /**
   * 跨页面预填（健康中心 → 对话的「AI 解读」入口）：
   * 视图导航到 /chat 后取出并写入输入框，取一次即清空。
   */
  const prefillDraft = ref('')

  let abortController: AbortController | null = null
  let throttle: ReturnType<typeof createRafThrottle<{ id: number | null; text: string }>> | null = null

  /* ---------------- 会话列表 ---------------- */

  async function loadSessions(): Promise<void> {
    sessions.value = await chatApi.listSessions()
  }

  /**
   * 加载指定会话。约定（接口文档 4.5）：默认加载【最后一页】并定位到底部。
   * 传 null 表示进入"新对话"空态。
   */
  async function loadSession(sessionId: number | null): Promise<void> {
    stopStream()
    currentSessionId.value = sessionId
    messages.value = []

    if (sessionId == null) return

    const first = await chatApi.listMessages(sessionId, 1, MESSAGES_PAGE_SIZE)
    const lastPage = first.pages > 1 ? first.pages : 1
    const page =
      lastPage === 1
        ? first
        : await chatApi.listMessages(sessionId, lastPage, MESSAGES_PAGE_SIZE)
    // records 按时间正序，直接可渲染
    messages.value = page.records.map((m: ChatMessage) => ({ ...m }))
  }

  /* ---------------- 乐观更新（§4.3） ---------------- */

  async function renameSession(id: number, title: string): Promise<void> {
    const target = sessions.value.find((s) => s.id === id)
    if (!target) return
    const old = target.title
    target.title = title
    try {
      await chatApi.renameSession(id, title)
    } catch {
      target.title = old // 提示已由 Axios 拦截器统一弹出，这里只回滚
    }
  }

  async function removeSession(id: number): Promise<void> {
    const idx = sessions.value.findIndex((s) => s.id === id)
    if (idx < 0) return
    const [removed] = sessions.value.splice(idx, 1)
    try {
      await chatApi.deleteSession(id)
    } catch {
      sessions.value.splice(idx, 0, removed)
      return
    }
    // 删的是当前会话 → 清数据；跳转由视图层决定（URL 是唯一真相）
    if (currentSessionId.value === id) {
      currentSessionId.value = null
      messages.value = []
    }
  }

  /* ---------------- 流式问答 ---------------- */

  /**
   * 发送提问（或重新生成）。
   * @param retryFromMessageId 重新生成时指定被替换的助手消息（接口文档 4.6）
   */
  async function sendMessage(text: string, retryFromMessageId?: number): Promise<void> {
    const question = text.trim()
    // 防重门禁必须放在函数入口，不能只靠 UI 禁用按钮（§5.2）
    if (!question || isStreaming.value) return

    // 危机干预：本地规则命中 → 展示求助指引，但【不拦截】提问（§11.2）
    if (hitsCrisisRule(question)) crisisVisible.value = true

    if (retryFromMessageId == null) {
      messages.value.push(localUserMessage(question))
    } else {
      // 重新生成：先移除被替换的助手回答，不重复插入用户消息
      const idx = messages.value.findIndex((m) => m.id === retryFromMessageId)
      if (idx >= 0) messages.value.splice(idx, 1)
    }

    messages.value.push(assistantPlaceholder())

    /*
     * ⚠️ 关键：必须【取回 push 之后的响应式代理】再修改。
     *
     * 若直接用 push 前那个 raw 对象（`const pending = assistantPlaceholder()`），
     * 之后对它的所有赋值都不会触发 Vue 更新——实测表现为：
     * 生成过程中界面一个字都不出，直到 done 之后别的响应式变化「顺带」
     * 把整段回答一次性刷出来（2026-09-18 浏览器实测：内容长度序列
     * 0,0,…,0,769）。流式输出这个核心体验等于完全失效，
     * 而 Node 侧单测覆盖不到（它不跑 Vue 渲染）。
     *
     * references 同理：走 raw 对象会导致「回答还没出来就能看到来源」失效。
     */
    const pending = messages.value[messages.value.length - 1]

    isStreaming.value = true
    abortController = new AbortController()

    /**
     * 合帧节流（ADR-016）：一帧内的增量合并成一次写入。
     * 对照解答模式下 delta 带 answerId —— 节流项携带归属，apply 时按段归并。
     */
    throttle = createRafThrottle<{ id: number | null; text: string }>((items) => {
      if (pending.segments?.length) {
        for (const seg of pending.segments) {
          seg.content += items
            .filter((it) => it.id === seg.answerId)
            .map((it) => it.text)
            .join('')
        }
        // 不带 answerId 的增量（异常流）：落到最后一段，不丢字
        const orphans = items.filter((it) => it.id === null)
        if (orphans.length) {
          pending.segments[pending.segments.length - 1].content += orphans
            .map((it) => it.text)
            .join('')
        }
      } else {
        pending.content += items.map((it) => it.text).join('')
      }
    })

    const sentAt = Date.now()
    let firstDeltaMs: number | null = null

    const handlers: SSEHandlers = {
      reference(payload): void {
        // 回答还没出来就能看到来源（02 v4.0 §4.3）
        pending.references = payload.chunks
        pending.retrieved = true
        pending.retrieveMs = Date.now() - sentAt
        // 改写检索词：reference 与 stage.retrieve_done 都可能携带，先到先用
        pending.rewrittenQuery = payload.rewrittenQuery ?? pending.rewrittenQuery ?? null
        // 阶段推进：仅当尚未收到 stage 事件时由 reference 驱动
        if (pending.stage === 'understand') pending.stage = 'generate'
      },

      // 显式阶段信号（02 v4.0 §4.3 🟢）；未收到时由 reference 兜底，行为不变
      stage(payload): void {
        // v4.0 字段名 stage；早期联调版 step——两者都收，防 mock/后端漂移
        const step = payload.stage ?? payload.step
        if (step === 'retrieve_start') {
          pending.stage = 'retrieve'
        } else if (step === 'retrieve_done') {
          pending.stage = 'retrieve'
          pending.retrieved = true
          pending.retrieveMs = Date.now() - sentAt
          if (payload.rewrittenQuery) pending.rewrittenQuery = payload.rewrittenQuery
        } else if (step === 'generate_start') {
          pending.stage = 'generate'
        }
      },

      delta(payload): void {
        if (firstDeltaMs === null) {
          firstDeltaMs = Date.now() - sentAt
          pending.firstDeltaMs = firstDeltaMs
        }
        throttle?.push({ id: payload.answerId ?? null, text: payload.content })
      },

      // 对照解答（🟡B-27）：先系统版后融合版，串行两段
      answer_start(payload: AnswerStartPayload): void {
        if (!pending.segments) pending.segments = []
        pending.segments.push({
          answerId: payload.answerId,
          source: payload.source,
          label: payload.label,
          content: ''
        })
      },

      answer_end(payload: AnswerEndPayload): void {
        const seg = pending.segments?.find((s) => s.answerId === payload.answerId)
        if (seg) seg.ended = true
      },

      done(payload: DonePayload): void {
        // ⚠️ 必须先 flush：done 携带全文，此刻 throttle 里可能还压着同一帧未提交的增量。
        // 若先覆盖全文再让调用方 finally 里的 flush 提交，残留增量会被追加到全文末尾
        // → 回答尾部出现 6~10 字的重复片段（测试报告 §21.2 D4）。
        // flush 幂等（提交后 pending 清空、handle 置 null），调用方的二次 flush 自动变空操作。
        throttle?.flush()
        // 全文校正（ADR-016）：单答案时 done.content 即全文；
        // 对照解答多段时以分段累积为准（content 的双答案拼接格式协议未定，不冒然覆盖）
        if (pending.segments?.length) {
          pending.content = pending.segments.map((s) => s.content).join('\n\n')
        } else {
          pending.content = payload.content
        }
        pending.latencyMs = payload.latencyMs
        pending.firstTokenMs = payload.firstTokenMs
        pending.finishStatus = payload.finishStatus
        pending.streaming = false
        pending.stage = 'done'
        // 健康注入与否以服务端为准（02 v4.0 done.healthUsed）
        pending.healthUsed = payload.healthUsed === true
        pending.healthReminder = payload.healthReminder ?? null
        pending.healthSuggestion = payload.healthSuggestion ?? null

        // messageId 必须判空：占位 id（负数）不能被 null 覆盖（Vue 重复 key / 重试失效）
        if (payload.messageId != null) {
          pending.id = payload.messageId
        }

        // sessionId 为空时由后端隐式新建会话；同样需判空
        if (currentSessionId.value == null && payload.sessionId != null) {
          currentSessionId.value = payload.sessionId
        }
        void loadSessions()
      },

      error(payload: { code: number; message: string }): void {
        pending.streaming = false
        pending.stage = 'done'
        if (payload.code === 2002) {
          // 置信度拒答（02 §4.6）：后端在 message 里携带引导话术，
          // 优先用服务端文案，本地常量仅兜底；有明显视觉区分，不伪装成正常回答
          pending.content = payload.message || REJECTED_ANSWER
          pending.isRejected = 1
        } else if (payload.code === 2004) {
          // 敏感意图拦截（V2 新增）：message 即安全引导话术，与拒答同路渲染
          pending.content = payload.message || REJECTED_ANSWER
          pending.isRejected = 1
        } else {
          showMessage(payload.message || '服务异常，请稍后重试', 'error')
        }
      }
    }

    try {
      await streamChat(
        {
          sessionId: currentSessionId.value,
          question,
          retryFromMessageId
        },
        handlers,
        abortController.signal
      )
    } finally {
      throttle?.flush()
      throttle = null
      abortController = null
      isStreaming.value = false

      if (pending.streaming) {
        // 流结束但没有 done（网络中断 / 异常断开）：
        // 保留已生成内容并标记未完成（ADR-015），前端渲染「已中断」+「重新生成」
        pending.streaming = false
        pending.finishStatus = 0
        pending.stage = 'done'
      }

      // 开发期核对端到端首字延迟（§8.2 埋点；生产指标由后端 first_token_ms 承担，ADR-017）
      if (import.meta.env.DEV && firstDeltaMs !== null) {
        console.debug(`[perf] 端到端首字延迟：${firstDeltaMs}ms`)
      }
    }
  }

  /** 停止生成：已生成内容保留，标记未完成（§5.2 / ADR-015） */
  function stopStream(): void {
    if (abortController == null) return
    abortController.abort()
    abortController = null
    throttle?.flush()
    throttle = null
    isStreaming.value = false

    const last = messages.value[messages.value.length - 1]
    if (last?.streaming) {
      last.streaming = false
      last.finishStatus = 0
      last.stage = 'done'
    }
  }

  /** 重新生成：找到被替换回答之前最近的一条用户提问 */
  async function retryMessage(messageId: number): Promise<void> {
    if (isStreaming.value) return
    const idx = messages.value.findIndex((m) => m.id === messageId)
    if (idx < 0) return
    for (let i = idx - 1; i >= 0; i--) {
      if (messages.value[i].role === 'user') {
        await sendMessage(messages.value[i].content, messageId)
        return
      }
    }
  }

  /**
   * 「重新提问」——用于本端流断开、客户端拿不到真实 messageId 的情形。
   * 与 retryMessage 的区别：
   *   retryMessage   带 retryFromMessageId，服务端原地替换那条助手消息
   *   resendFromMsg  不带该参数，作为一条全新提问重发（先移除中断的占位）
   *
   * 为什么必须分开：本地占位 id 是负数，传给后端必然失败。
   * 诚实地区分两种场景，好过给一个点了就报错的按钮。
   */
  async function resendFromMessage(messageId: number): Promise<void> {
    if (isStreaming.value) return
    const idx = messages.value.findIndex((m) => m.id === messageId)
    if (idx < 0) return
    for (let i = idx - 1; i >= 0; i--) {
      if (messages.value[i].role === 'user') {
        const question = messages.value[i].content
        messages.value.splice(idx, 1)
        await sendMessage(question)
        return
      }
    }
  }

  function dismissCrisis(): void {
    crisisVisible.value = false
  }

  /** 健康中心等外部页面把问题塞进输入框（导航到 /chat 后由视图取出） */
  function queuePrefill(text: string): void {
    prefillDraft.value = text
  }

  function consumePrefill(): string {
    const text = prefillDraft.value
    prefillDraft.value = ''
    return text
  }

  /** 退出登录时清空（不持久化的数据，见 §4） */
  function reset(): void {
    stopStream()
    sessions.value = []
    currentSessionId.value = null
    messages.value = []
    crisisVisible.value = false
    prefillDraft.value = ''
  }

  return {
    sessions,
    currentSessionId,
    messages,
    isStreaming,
    crisisVisible,
    prefillDraft,
    loadSessions,
    loadSession,
    renameSession,
    removeSession,
    sendMessage,
    stopStream,
    retryMessage,
    resendFromMessage,
    dismissCrisis,
    queuePrefill,
    consumePrefill,
    reset
  }
})
