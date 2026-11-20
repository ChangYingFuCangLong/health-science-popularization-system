/* ============================================================
 * 契约 mock · 路由处理器
 *
 * 与真实后端的差异只有一处、且必须显式标注：
 *   **未覆盖的 /api 路径返回业务码 404**（HTTP 200 包装），
 *   让页面走各自的 silent 降级分支，而不是抛网络错误刷屏。
 *   这样「哪些接口还没 mock」在控制台里是一眼可见的清单。
 *
 * 覆盖范围 = 用户端全部常用接口 + 个人知识库全量契约。
 * 管理端未覆盖（本轮不涉及，落在 404 分支）。
 * ============================================================ */

import type { ServerResponse } from 'node:http'
import * as store from './store'
import { routeV4, kindOfSession, readyFileCount, appendMessage } from './v4'

export type MockResult =
  | { kind: 'json'; body: unknown }
  | { kind: 'sse'; write: (res: ServerResponse) => void }

export const ok = (data: unknown): MockResult => ({ kind: 'json', body: { code: 200, message: 'success', data } })
export const fail = (code: number, message: string): MockResult => ({ kind: 'json', body: { code, message, data: null } })

function page<T>(records: T[], pageNum: number, pageSize: number) {
  const total = records.length
  const pages = Math.max(1, Math.ceil(total / pageSize))
  const current = Math.min(Math.max(pageNum, 1), pages)
  return {
    total,
    pages,
    current,
    size: pageSize,
    records: records.slice((current - 1) * pageSize, current * pageSize)
  }
}

/** 展示用的处理链路回答（与 store 里会话 1 的落库消息保持一致） */
const ANSWER_TEXT =
  '高血压的日常管理主要围绕生活方式干预展开。\n\n' +
  '**一、减少钠盐摄入**\n每人每日食盐摄入量逐步降至 5g 以下，同时增加富含钾的新鲜蔬菜水果。\n\n' +
  '**二、控制体重与规律运动**\nBMI 建议控制在 24 以下；每周 5~7 天中等强度有氧运动，每次 30 分钟。\n\n' +
  '**三、结合你上传的资料**\n你上传的体检报告中记录了近期血压与血脂数值，其结论与指南建议的高危因素一致，建议按报告中医生建议的随访周期复查 [2]。\n\n' +
  '以上为科普信息，不能替代医生的诊断与处方。'

const ANSWER_REFS = [
  {
    chunkId: 8123,
    docId: 8,
    doc: '中国高血压防治指南（2024 年修订版）',
    section: '三、日常管理',
    digest: '减少钠盐摄入、增加钾摄入、控制体重、戒烟限酒、规律运动、减轻精神压力。',
    score: 0.812,
    scope: 'global'
  },
  {
    chunkId: 88301,
    docId: 1001,
    doc: '我的体检报告 2026',
    section: '二、检查结果',
    digest: '收缩压 148mmHg，舒张压 92mmHg，低密度脂蛋白 3.9mmol/L，医生建议 4 周后复查。',
    score: 0.684,
    scope: 'personal'
  }
]


/* 画像版回答已由 v4 流式分支的 fusedAnswer 取代（kind='health' 三态路由，2026-10-25）：
 * 原理保留——画像只影响生成不影响检索，专属/普通对话的引用列表相同、差别在正文，
 * 这是「画像是否真的注入」唯一可被看见的证据。 */

/** 从 multipart 原文里粗暴取文件名——mock 够用，不引入 multer */
function filenameOf(raw: string): string {
  const m = /filename="([^"]+)"/.exec(raw)
  return m ? m[1] : '上传文件'
}

export function parseJson(raw: string): Record<string, unknown> {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return {}
  }
}

/** 取字符串字段：非字符串一律视为空串（不把 undefined/null 传进内部状态） */
function str(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

/** 取字符串数组字段：过滤掉非字符串元素 */
function arr(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
}

/* ============================================================
 * 主分发
 * ============================================================ */

export function route(
  method: string,
  pathname: string,
  query: URLSearchParams,
  rawBody: string
): MockResult {
  const num = (v: string | null, d = 1) => {
    const n = Number(v)
    return Number.isFinite(n) && n > 0 ? n : d
  }

  // v4 契约增补（02 v4.0 🟡）：会话/目录/KV/报告/文档解惑——先于主分发
  const v4 = routeV4(method, pathname, query, rawBody)
  if (v4) return v4

  /* ---------------- 认证 ---------------- */
  if (method === 'POST' && pathname === '/api/auth/login') {
    const body = parseJson(rawBody)
    const username = String(body.username ?? '')
    const password = String(body.password ?? '')
    const u = store.MOCK_USERS[username]
    if (!u || u.password !== password) return fail(1002, '用户名或密码错误')
    return ok({
      token: `mock-token.${username}.${Date.now()}`,
      expiresIn: 7200,
      userInfo: store.userInfo(username)
    })
  }
  if (pathname === '/api/auth/me') {
    // mock 下固定返回普通用户；要切管理员就把用户名换成 admin
    return ok(store.userInfo('renhao'))
  }
  if (method === 'POST' && pathname === '/api/auth/logout') return ok(null)
  if (method === 'POST' && pathname === '/api/auth/register') return ok(null)

  /* ---------------- 会话 ---------------- */
  if (method === 'GET' && pathname === '/api/chat/sessions') return ok(store.listSessions())

  // #57 查询专属对话：不存在 → data: null（不是 404，「还没建」是正常状态）
  if (method === 'GET' && pathname === '/api/chat/sessions/portrait') {
    return ok(store.findPortraitSession())
  }
  // #58 创建专属对话
  if (method === 'POST' && pathname === '/api/chat/sessions/portrait') {
    if (!store.portraitHasContent()) {
      return fail(2008, '请先填写健康画像，再建立专属对话')
    }
    if (store.findPortraitSession()) {
      return fail(2009, '专属对话已存在，每位用户至多一条')
    }
    return ok(store.createSession('portrait'))
  }

  const msgMatch = /^\/api\/chat\/sessions\/(\d+)\/messages$/.exec(pathname)
  if (method === 'GET' && msgMatch) {
    const id = Number(msgMatch[1])
    const list = store.MESSAGES[id] ?? []
    return ok(page(list, num(query.get('pageNum')), num(query.get('pageSize'), 50)))
  }
  const sessionIdMatch = /^\/api\/chat\/sessions\/(\d+)$/.exec(pathname)
  if (sessionIdMatch) {
    const sid = Number(sessionIdMatch[1])
    if (method === 'PUT') {
      const title = String(parseJson(rawBody).title ?? '').trim()
      if (!store.renameSession(sid, title || '新对话')) return fail(404, '会话不存在')
      return ok(null)
    }
    if (method === 'DELETE') {
      // 删除专属对话后 uk_session_portrait_active 不再约束它 → 可重建（需求 C5）
      if (!store.removeSession(sid)) return fail(404, '会话不存在')
      return ok(null)
    }
  }
  if (method === 'POST' && pathname === '/api/chat/sessions') {
    // 02 §4.1 只建普通会话；专属对话走 #58（契约 §四 落点 2：两处入口语义必须分开）
    const created = store.createSession('normal')
    return ok(created)
  }
  // 追问建议：真实后端首次 LLM 生成较慢，前端本就有 404 → 客户端派生兜底
  if (/^\/api\/chat\/messages\/\d+\/suggestions$/.test(pathname)) return fail(404, 'mock 未实现追问建议')

  /* ---------------- 流式问答（SSE，02 v4.0 §4.3 全协议） ---------------- */
  if (method === 'POST' && pathname === '/api/chat/stream') {
    const body = parseJson(rawBody)
    const question = String(body.question ?? '').trim()

    // v4：检索范围由会话 kind 决定（kbScope 参数已废除）
    let effectiveSid = body.sessionId == null ? null : Number(body.sessionId)
    if (effectiveSid == null) {
      // 隐式新建恒 normal（02 §1.5 矩阵：health/file 必须显式传已有会话）
      const created = store.createSession('normal')
      if (question) store.renameSession(created.id, question.slice(0, 20))
      effectiveSid = created.id
    }

    const kind = kindOfSession(effectiveSid)
    const qaReady = kind === 'file' ? readyFileCount(effectiveSid) : 1

    // 引用按 kind 组装：file = 会话文件（scope=file，doc=文件名）；health = 权威+个人资料；normal = 权威
    const refs =
      kind === 'file'
        ? [
            { chunkId: 9101, docId: 5001, doc: '上传文件 1', section: '', digest: '文件集中关于该问题的原文段落（mock）。', score: 0.88, scope: 'file' }
          ]
        : kind === 'health'
          ? [
              ...ANSWER_REFS.filter((r) => r.scope === 'global'),
              { chunkId: 9201, docId: 5002, doc: '我的体检报告', section: '血脂', digest: '低密度脂蛋白 3.2 mmol/L（2026-09）。', score: 0.9, scope: 'personal' }
            ]
          : ANSWER_REFS.filter((r) => r.scope === 'global')

    // 双答案（对照解答）触发：个人解惑 且 个人资料参与（mock 演示态恒触发）
    const dual = kind === 'health'

    const globalAnswer = kind === 'file' ? '根据你上传的文件：mock 文档解惑回答。建议结合文件原文核对关键数值，如有疑问以原始文件为准。' : ANSWER_TEXT
    const fusedAnswer = '结合你的个人资料：你 9 月的体检报告显示低密度脂蛋白 3.2 mmol/L 偏高。建议在一般性建议基础上，优先控制膳食胆固醇摄入（每周不超过 3 个蛋黄），三个月后复查血脂。'

    // healthUsed / healthReminder 与服务端口径一致（mock 演示态）
    const healthUsed = kind === 'health'
    const reminder = kind === 'health' && question.includes('体重')
      ? { field: 'weight', recordedValue: '72.5', statedValue: '70' }
      : null

    return {
      kind: 'sse',
      write(res: ServerResponse) {
        res.writeHead(200, {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache, no-transform',
          Connection: 'keep-alive',
          'X-Accel-Buffering': 'no'
        })
        const send = (event: string, data: unknown) =>
          res.write(`event: ${event}
data: ${JSON.stringify(data)}

`)

        send('stage', { stage: 'retrieve_start' })
        setTimeout(() => {
          // 空文件集提问 → 400 引导上传（12 号行为裁决，不走 2002）
          if (kind === 'file' && qaReady === 0) {
            send('error', { code: 400, message: '请先上传文件（解析完成后即可提问）' })
            res.end()
            return
          }
          send('stage', { stage: 'retrieve_done', rewrittenQuery: '高血压 日常管理 注意事项' })
          send('reference', { rewrittenQuery: '高血压 日常管理 注意事项', chunks: refs })
          send('stage', { stage: 'generate_start' })

        // 消息落库（一问一答；health 双答案记 2 条助手消息）
        store.bumpSession(effectiveSid, dual ? 3 : 2)
        if (kind !== 'normal') appendMessage(effectiveSid, 'user', question)

        const answers: Array<{ answerId: number; source: 'global' | 'fused'; label: string; text: string }> = dual
          ? [
              { answerId: 1, source: 'global', label: '系统版', text: globalAnswer },
              { answerId: 2, source: 'fused', label: '个人版', text: fusedAnswer }
            ]
          : [{ answerId: 1, source: 'global', label: '', text: globalAnswer }]

        let ai = 0
        const streamAnswer = (): void => {
          const ans = answers[ai]
          if (dual) send('answer_start', { answerId: ans.answerId, source: ans.source, label: ans.label })
          const chunks = ans.text.match(/.{1,12}/gs) ?? []
          let i = 0
          const timer = setInterval(() => {
            if (i >= chunks.length) {
              clearInterval(timer)
              if (dual) send('answer_end', { answerId: ans.answerId, messageId: 900 + ai })
              if (kind !== 'normal') appendMessage(effectiveSid, 'assistant', ans.text)
              ai += 1
              if (ai < answers.length) {
                setTimeout(streamAnswer, 260)
                return
              }
              send('done', {
                messageId: 900 + Math.floor(Math.random() * 100),
                sessionId: effectiveSid,
                latencyMs: dual ? 5400 : 2860,
                firstTokenMs: 742,
                finishStatus: 1,
                content: dual ? answers.map((a) => a.text).join('\n\n') : globalAnswer,
                answerCount: dual ? 2 : undefined,
                healthUsed: healthUsed || undefined,
                healthReminder: reminder,
                healthSuggestion: healthUsed ? '以上建议结合了你的健康信息，重大调整请咨询医生。' : undefined
              })
              res.end()
              return
            }
            send('delta', dual ? { content: chunks[i], answerId: ans.answerId } : { content: chunks[i] })
            i += 1
          }, 28)
        }
        streamAnswer()
        }, 320)
      }
    }
  }

  /* ---------------- 全局知识库（登录即可） ---------------- */
  if (pathname === '/api/knowledge/categories') return ok(store.GLOBAL_CATEGORIES)

  if (pathname === '/api/knowledge/articles') {
    const category = query.get('category')
    const keyword = query.get('keyword')?.trim()
    let list = store.GLOBAL_ARTICLES
    if (category) list = list.filter((a) => a.category === category)
    if (keyword) list = list.filter((a) => a.title.includes(keyword))
    return ok(page(list, num(query.get('pageNum')), num(query.get('pageSize'), 20)))
  }

  const relatedMatch = /^\/api\/knowledge\/articles\/(\d+)\/related$/.exec(pathname)
  if (relatedMatch) {
    const id = Number(relatedMatch[1])
    return ok({
      articles: store.GLOBAL_ARTICLES.filter((a) => a.id !== id)
        .slice(0, 4)
        .map((a, i) => ({ id: a.id, title: a.title, category: a.category, similarity: 0.83 - i * 0.07 }))
    })
  }

  const articleMatch = /^\/api\/knowledge\/articles\/(\d+)$/.exec(pathname)
  if (articleMatch) return ok(store.articleDetailGlobal(Number(articleMatch[1])))

  /* ============================================================
   * 健康画像（契约 #53–#56）
   * ============================================================ */
  if (pathname === '/api/user/portrait/options' && method === 'GET') {
    return ok(store.PORTRAIT_OPTIONS)
  }
  if (pathname === '/api/user/portrait') {
    if (method === 'GET') return ok(store.portraitView())
    if (method === 'PUT') {
      const body = parseJson(rawBody)
      // 范围校验与库级 CHECK 同口径（§5.4）：越界 → 400（不新增 3xxx，那是健康指标的码）
      const num = (v: unknown) => (v == null || v === '' ? null : Number(v))
      const birthYear = num(body.birthYear)
      const heightCm = num(body.heightCm)
      const weightKg = num(body.weightKg)
      const sleepHours = num(body.sleepHours)
      if (birthYear !== null && !(birthYear >= 1900 && birthYear <= 2100)) {
        return fail(400, '出生年份应在 1900~2100 之间')
      }
      if (heightCm !== null && !(heightCm >= 80 && heightCm <= 250)) {
        return fail(400, '身高应在 80~250 cm 之间')
      }
      if (weightKg !== null && !(weightKg >= 20 && weightKg <= 300)) {
        return fail(400, '体重应在 20~300 kg 之间')
      }
      if (sleepHours !== null && !(sleepHours >= 0 && sleepHours <= 24)) {
        return fail(400, '睡眠时长应在 0~24 小时之间')
      }
      store.savePortrait({
        // 逐字段取值而不是整体 spread：客户端输入不直接灌进内部状态
        gender: str(body.gender),
        birthYear,
        heightCm,
        weightKg,
        conditions: arr(body.conditions),
        allergies: str(body.allergies),
        medications: str(body.medications),
        exercise: str(body.exercise),
        sleepHours,
        smoking: str(body.smoking),
        drinking: str(body.drinking),
        diet: arr(body.diet),
        goals: arr(body.goals),
        note: str(body.note)
      })
      return ok(null)
    }
    if (method === 'DELETE') {
      store.clearPortrait()
      return ok(null)
    }
  }

  /* ============================================================
   * 个人知识库（契约 #44–#52）
   * ============================================================ */
  if (method === 'GET' && pathname === '/api/user/kb/stats') return ok(store.stats())

  if (method === 'GET' && pathname === '/api/user/kb/documents') {
    store.tick()
    const keyword = query.get('keyword')?.trim()
    const status = query.get('status')
    let list = [...store.personalDocs]
    if (keyword) list = list.filter((d) => d.title.includes(keyword))
    if (status) list = list.filter((d) => String(d.status) === status)
    // 契约 #45：列表不回进度字段，进度由 #47 单独轮询
    const records = list.map((d) => ({
      id: d.id,
      title: d.title,
      fileType: d.fileType,
      fileSize: d.fileSize,
      status: d.status,
      failReason: d.failReason,
      chunkCount: d.chunkCount,
      createTime: d.createTime
    }))
    return ok(page(records, num(query.get('pageNum')), num(query.get('pageSize'), 10)))
  }

  if (method === 'POST' && pathname === '/api/user/kb/documents/upload') {
    const filename = filenameOf(rawBody)
    const dot = filename.lastIndexOf('.')
    const ext = dot > 0 ? filename.slice(dot + 1).toLowerCase() : ''
    const allowed = ['txt', 'md', 'html', 'htm', 'pdf', 'docx']
    if (!allowed.includes(ext)) {
      return fail(2007, `不支持的文件类型：${ext || '未知'}（允许 ${allowed.join('/')}）`)
    }
    // 配额前置校验（契约 §5.2）：mock 用近似体积（multipart 原始长度）
    const size = Math.max(1024, rawBody.length)
    const s = store.stats()
    if (s.documentCount >= store.QUOTA.maxDocuments) {
      return fail(2006, `文档数已达上限 ${store.QUOTA.maxDocuments} 篇，请先删除不再需要的资料`)
    }
    if (s.totalSize + size > store.QUOTA.maxTotalSize) {
      return fail(2006, '个人库总占用已接近上限（100MB），请先删除部分资料')
    }
    const title = dot > 0 ? filename.slice(0, dot) : filename
    const doc = store.addDoc(title, ext, size)
    return ok({ id: doc.id, status: doc.status })
  }

  const progressMatch = /^\/api\/user\/kb\/documents\/(\d+)\/progress$/.exec(pathname)
  if (method === 'GET' && progressMatch) {
    const doc = store.findDoc(Number(progressMatch[1]))
    if (!doc) return fail(404, '文档不存在')
    const p = store.progressOf(doc)
    return ok({
      documentId: doc.id,
      status: doc.status,
      done: p.done,
      total: p.total,
      failReason: doc.failReason
    })
  }

  const chunksMatch = /^\/api\/user\/kb\/documents\/(\d+)\/chunks$/.exec(pathname)
  if (method === 'GET' && chunksMatch) {
    const doc = store.findDoc(Number(chunksMatch[1]))
    if (!doc) return fail(404, '文档不存在')
    if (doc.status !== 2) return fail(2003, '文档尚未完成索引，暂无分块')
    return ok(page(store.chunksOf(doc), num(query.get('pageNum')), num(query.get('pageSize'), 20)))
  }

  const rebuildMatch = /^\/api\/user\/kb\/documents\/(\d+)\/rebuild$/.exec(pathname)
  if (method === 'POST' && rebuildMatch) {
    const id = Number(rebuildMatch[1])
    if (!store.rebuildDoc(id)) return fail(404, '文档不存在')
    return ok(null)
  }

  const docMatch = /^\/api\/user\/kb\/documents\/(\d+)$/.exec(pathname)
  if (method === 'DELETE' && docMatch) {
    if (!store.removeDoc(Number(docMatch[1]))) return fail(404, '文档不存在')
    return ok(null)
  }

  if (pathname === '/api/user/kb/settings') {
    if (method === 'GET') {
      // enabled 是只读派生字段（契约 #51）：有 ≥1 篇完成索引的文档才算可用
      return ok({ ...store.personalSettings, enabled: store.stats().readyCount > 0 })
    }
    if (method === 'PUT') {
      const body = parseJson(rawBody)
      if (typeof body.includeInChat === 'boolean') store.personalSettings.includeInChat = body.includeInChat
      const scope = String(body.defaultScope ?? '')
      if (scope === 'all' || scope === 'global' || scope === 'personal') {
        store.personalSettings.defaultScope = scope
      }
      return ok(null)
    }
  }

  /* ---------------- 其余：健康中心 / 个人中心 ---------------- */
  if (pathname === '/api/health/metrics') {
    if (method === 'GET') return ok({ records: [] })
    return ok({ id: 1 })
  }
  if (/^\/api\/health\/metrics\/\d+$/.test(pathname)) return ok(null)
  if (pathname === '/api/user/profile') return ok(null)
  if (pathname === '/api/user/password') return ok(null)

  /* ---------------- 未覆盖 ---------------- */
  return fail(404, `mock 未覆盖：${method} ${pathname}`)
}

/* ============================================================
 * 重置端点（仅 dev mock，真实后端不存在）
 * ============================================================ */

/**
 * `POST /api/__mock/reset` —— 把内存态恢复到初始。
 * 验收脚本开头调用它，套件即可反复运行；否则第二次跑的起点已被上一轮改动过。
 */
export function resetRoute(method: string, pathname: string): boolean {
  if (method === 'POST' && pathname === '/api/__mock/reset') {
    store.resetAll()
    return true
  }
  return false
}
