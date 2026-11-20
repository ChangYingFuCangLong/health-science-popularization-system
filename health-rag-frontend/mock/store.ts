/* ============================================================
 * 契约 mock · 内存数据源
 *
 * 定位：开发期「后端不在线」时的数据替身。所有字段类型与
 *       docs/V2/07-待实现接口-用户个人知识库.md 的契约逐字对齐——
 *       **mock 是契约的测试用例，不是随手编的假数据**。
 *
 * 三条设计约束：
 *   1. 状态是内存态、进程存活期间有效（重启 dev server 即重置），
 *      这样「上传 → 处理中 → 完成 → 分块预览」是可走通的真实链路；
 *   2. 进度不靠定时器推进，而是按「已过时间」惰性求值——
 *      没有 setInterval 就没有泄漏，也不会因为页面切走而停摆；
 *   3. 本文件只在 Node 侧被 vite.config.ts 引用，**不进浏览器产物**。
 * ============================================================ */

/** 文档处理状态（与 02 契约 §6.3 同款：0 待处理 / 1 解析中 / 2 完成 / 3 失败） */
export type DocStatus = 0 | 1 | 2 | 3

export interface MockKbDoc {
  id: number
  title: string
  fileType: string
  fileSize: number
  status: DocStatus
  failReason: string | null
  chunkCount: number
  createTime: string
  /** 开始处理的时刻（用于惰性推算进度） */
  startedAt: number
  /** 预计总块数（解析完成后才有真值，这里按体积估算） */
  progressTotal: number
  /**
   * 处理时长下限。小文件按块数算只够几十毫秒，上传瞬间就完成——
   * 于是「处理中」这一支永远看不到，也无法验证进度轮询。
   * 给上传路径一个可观察的窗口，让状态机每一档都真的能被看到。
   */
  minDurationMs: number
}

export interface MockChunk {
  id: number
  chunkIndex: number
  section: string
  content: string
  charCount: number
}

/* ---------------- 工具 ---------------- */

function fmt(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  )
}

/** 每块耗时（毫秒）。取小值让演示不用等——真后端是秒级 */
const MS_PER_CHUNK = 90

/** 上传后的最短处理时长：保证「处理中 + 真实进度」这一支可见可验 */
const MIN_PROCESS_MS = 6000

/** 处理总时长 = max(块数 × 每块耗时, 时长下限) */
function durationOf(doc: MockKbDoc): number {
  return Math.max(doc.progressTotal * MS_PER_CHUNK, doc.minDurationMs)
}

/* ---------------- 状态 ---------------- */

/**
 * ⚠️ 序号起点必须高于种子文档的最大 id（1005）。
 * 曾经从 1000 起自增，于是**第一次上传正好拿到 1001**，与种子文档
 * 「我的体检报告 2026」撞号——两个文档同 id，检索/预览会随机命中其中之一，
 * 表现为「溯源高亮时有时无」。这类撞号会让验收在错误的行为上"通过"，比直接报错更危险。
 */
let docSeq = 2000
let chunkSeq = 88000

/**
 * 个人库文档（当前登录用户）。初始预置 5 篇，覆盖全部四种状态：
 * 已索引入库 / 处理中 / 处理失败——一进页面就能看到所有状态分支，
 * 不必手动造场景（这是 mock 的主要价值）。
 *
 * 用工厂函数而不是字面量常量：`resetAll()` 要能把状态恢复成"刚启动"的样子。
 */
function seedDocs(): MockKbDoc[] {
  return [
  {
    id: 1001,
    title: '我的体检报告 2026',
    fileType: 'pdf',
    fileSize: 2_097_152,
    status: 2,
    failReason: null,
    chunkCount: 87,
    createTime: fmt(Date.now() - 3 * 86400_000),
    startedAt: 0,
    progressTotal: 87,
    minDurationMs: 0
  },
  {
    id: 1002,
    title: '高血压家庭自测记录（近三个月）',
    fileType: 'md',
    fileSize: 48_512,
    status: 2,
    failReason: null,
    chunkCount: 24,
    createTime: fmt(Date.now() - 36 * 3600_000),
    startedAt: 0,
    progressTotal: 24,
    minDurationMs: 0
  },
  {
    id: 1003,
    title: '营养师给的膳食建议.docx',
    fileType: 'docx',
    fileSize: 331_776,
    status: 2,
    failReason: null,
    chunkCount: 41,
    createTime: fmt(Date.now() - 5 * 3600_000),
    startedAt: 0,
    progressTotal: 41,
    minDurationMs: 0
  },
  {
    id: 1004,
    title: '睡眠与作息调整计划（读书笔记）.pdf',
    fileType: 'pdf',
    fileSize: 3_355_443,
    status: 1,
    failReason: null,
    chunkCount: 0,
    createTime: fmt(Date.now() - 8_000),
    // 刻意留一段较长的处理时长：演示/截图时能稳定看到「处理中 + 真实进度」这一支，
    // 走完（520 × 90ms ≈ 47s）后自动转「已索引入库」，无需人工干预
    startedAt: Date.now() - 8_000,
    progressTotal: 520,
    minDurationMs: 0
  },
  {
    id: 1005,
    title: '扫描版药盒说明书',
    fileType: 'pdf',
    fileSize: 1_572_864,
    status: 3,
    failReason: '未提取到有效文本（扫描版无文本层，需 OCR，本版本不支持）',
    chunkCount: 0,
    createTime: fmt(Date.now() - 2 * 86400_000),
    startedAt: 0,
    progressTotal: 0,
    minDurationMs: 0
  }
  ]
}

export const personalDocs: MockKbDoc[] = seedDocs()

/** 当前用户个人库偏好（契约 #51/#52） */
export const personalSettings = {
  enabled: true,
  defaultScope: 'all' as 'all' | 'global' | 'personal',
  includeInChat: true
}

/** 配额默认值（契约 §5.2） */
export const QUOTA = {
  maxDocuments: 20,
  maxTotalSize: 104_857_600,
  maxTotalChunks: 3000
}

/**
 * 惰性推进：把「解析中」的文档按已过时间折算成进度。
 * 每块 MS_PER_CHUNK 毫秒，走完即置完成。
 */
export function tick(): void {
  const now = Date.now()
  for (const doc of personalDocs) {
    if (doc.status !== 1) continue
    const elapsed = now - doc.startedAt
    if (elapsed >= durationOf(doc)) {
      doc.status = 2
      doc.chunkCount = doc.progressTotal
    }
  }
}

export function findDoc(id: number): MockKbDoc | undefined {
  tick()
  return personalDocs.find((d) => d.id === id)
}

export function progressOf(doc: MockKbDoc): { done: number; total: number | null } {
  if (doc.status === 1) {
    const ratio = Math.min(1, (Date.now() - doc.startedAt) / durationOf(doc))
    return { done: Math.floor(doc.progressTotal * ratio), total: doc.progressTotal }
  }
  if (doc.status === 2) return { done: doc.chunkCount, total: doc.chunkCount }
  return { done: 0, total: null }
}

/** 概览（契约 #44） */
export function stats() {
  tick()
  const live = personalDocs
  const documentCount = live.length
  const chunkCount = live.reduce((s, d) => s + d.chunkCount, 0)
  const totalSize = live.reduce((s, d) => s + d.fileSize, 0)
  const pendingChunks = live
    .filter((d) => d.status === 1)
    .reduce((s, d) => s + (d.progressTotal - progressOf(d).done), 0)

  return {
    documentCount,
    chunkCount,
    totalSize,
    readyCount: live.filter((d) => d.status === 2).length,
    processingCount: live.filter((d) => d.status === 1).length,
    failedCount: live.filter((d) => d.status === 3).length,
    pendingChunks,
    quota: {
      ...QUOTA,
      usedDocuments: documentCount,
      usedTotalSize: totalSize,
      usedTotalChunks: chunkCount
    }
  }
}

/** 上传（契约 #46）：只登记元信息，不做真实解析 */
export function addDoc(title: string, fileType: string, fileSize: number): MockKbDoc {
  docSeq += 1
  // 体积估算块数：约 1 块 / 2KB，钳在 6~120 之间（演示既不至于秒完，也不会等太久）
  const estimated = Math.max(6, Math.min(120, Math.round(fileSize / 2048)))
  const doc: MockKbDoc = {
    id: docSeq,
    title,
    fileType,
    fileSize,
    status: 1,
    failReason: null,
    chunkCount: 0,
    createTime: fmt(Date.now()),
    startedAt: Date.now(),
    progressTotal: estimated,
    minDurationMs: MIN_PROCESS_MS
  }
  personalDocs.unshift(doc)
  return doc
}

/** 重新向量化（契约 #50）：回到解析中 */
export function rebuildDoc(id: number): boolean {
  const doc = findDoc(id)
  if (!doc) return false
  doc.status = 1
  doc.failReason = null
  doc.chunkCount = 0
  doc.progressTotal = Math.max(6, doc.progressTotal || 12)
  doc.startedAt = Date.now()
  doc.minDurationMs = MIN_PROCESS_MS
  return true
}

export function removeDoc(id: number): boolean {
  const idx = personalDocs.findIndex((d) => d.id === id)
  if (idx < 0) return false
  personalDocs.splice(idx, 1)
  return true
}

/* ---------------- 分块（惰性生成，稳定可复现） ---------------- */

const SECTION_POOL = [
  '一、基本信息',
  '二、检查结果',
  '三、医生建议',
  '四、生活方式调整',
  '五、随访计划'
]

/**
 * 引用卡片摘要的**原文出处**：让 mock 的分块正文里真的出现这段文字。
 * 不这么做的话，「从引用卡片溯源 → 分块高亮」在 mock 下永远命中不了 ——
 * 那这条链路就成了只有形状、没有内容的假路径，而"高亮是否真的命中"
 * 恰恰是「溯源闭环」唯一的证据。
 */
const DOC_SNIPPETS: Record<number, string> = {
  1001: '收缩压 148mmHg，舒张压 92mmHg，低密度脂蛋白 3.9mmol/L，医生建议 4 周后复查。',
  1002: '近三个月家庭自测血压平均 142/89mmHg，晨起读数偏高，服药依从性良好。'
}

export function chunksOf(doc: MockKbDoc): MockChunk[] {
  const out: MockChunk[] = []
  const snippet = DOC_SNIPPETS[doc.id]
  for (let i = 0; i < doc.chunkCount; i++) {
    chunkSeq += 1
    const section = SECTION_POOL[i % SECTION_POOL.length]
    const content =
      i === 1 && snippet
        ? // 命中段：真实嵌入引用摘要原文，供高亮定位验证
          `【${doc.title} · ${section}】${snippet}` +
          `本段其余内容为原文续表：真实环境下这里是从你的文件里按 500 字窗、` +
          `80 字重叠切出的文字，检索命中后其 digest 会被拼进大模型上下文。`
        : `【${doc.title} · ${section}】` +
          `这是第 ${i + 1} 段检索单元的内容示例。真实环境下这里是从你的文件里` +
          `按 500 字窗、80 字重叠切出的原文片段；检索命中后，该片段的 digest 会被` +
          `拼进大模型的上下文，并在回答的引用来源里标注为「我的资料」。` +
          `段落划分优先落在自然段边界，避免把一句话拦腰截断。`
    out.push({
      id: chunkSeq,
      chunkIndex: i,
      section,
      content,
      charCount: content.length
    })
  }
  return out
}

/* ---------------- 全局权威知识库（供其他页面不白屏） ---------------- */

export const GLOBAL_CATEGORIES = [
  { name: '疾病防治', count: 96 },
  { name: '营养膳食', count: 74 },
  { name: '运动健身', count: 51 },
  { name: '心理健康', count: 43 },
  { name: '慢病管理', count: 38 }
]

export const GLOBAL_ARTICLES = [
  { id: 8, title: '中国高血压防治指南（2024 年修订版）', category: '疾病防治', source: '国家卫健委', chunkCount: 612, createTime: '2026-09-12 10:04:22' },
  { id: 12, title: '成人高血压食养指南', category: '营养膳食', source: '国家卫健委', chunkCount: 208, createTime: '2026-09-12 10:11:07' },
  { id: 21, title: '中国居民膳食指南（2022）', category: '营养膳食', source: '中国营养学会', chunkCount: 341, createTime: '2026-09-13 15:32:40' },
  { id: 33, title: '健康素养 66 条（2024 年版）', category: '疾病防治', source: '国家卫健委', chunkCount: 137, createTime: '2026-09-14 09:20:15' },
  { id: 47, title: '成年人身体活动指南', category: '运动健身', source: '中国疾控中心', chunkCount: 96, createTime: '2026-09-14 09:44:51' },
  { id: 58, title: '睡眠障碍诊疗手册', category: '心理健康', source: '默沙东诊疗手册', chunkCount: 274, createTime: '2026-09-15 14:02:33' }
]

export function articleDetailGlobal(id: number) {
  const base = GLOBAL_ARTICLES.find((a) => a.id === id) ?? GLOBAL_ARTICLES[0]
  return {
    id: base.id,
    title: base.title,
    source: base.source,
    category: base.category,
    chunkCount: base.chunkCount,
    content: `（契约 §5.3 示例）本文档共 ${base.chunkCount} 个分块。正文由分块拼接还原。`,
    sections: [
      { title: '一、概述', content: '高血压是最常见的慢性病之一，也是心脑血管病最主要的危险因素。' },
      { title: '二、诊断标准', content: '在未使用降压药物的情况下，非同日 3 次测量血压，收缩压 ≥140mmHg 和/或舒张压 ≥90mmHg。' },
      { title: '三、日常管理', content: '减少钠盐摄入、增加钾摄入、控制体重、戒烟限酒、规律运动、减轻精神压力。' }
    ]
  }
}

/* ---------------- 会话（真实状态机，供对话页与专属对话验证） ---------------- */

export interface MockSession {
  id: number
  title: string
  /** 契约 §5.4：normal 普通对话 / portrait 专属对话 */
  kind: 'normal' | 'portrait'
  messageCount: number
  createTime: string
  updateTime: string
}

export const PORTRAIT_SESSION_TITLE = '我的专属对话'

let sessionSeq = 100

/** 可变状态：新建/改名/删除都真的生效，否则 C3/C4/C5 三条需求无从验证 */
function seedSessions(): MockSession[] {
  return [
    {
      id: 1,
      title: '高血压患者日常注意事项',
      kind: 'normal',
      messageCount: 6,
      createTime: fmt(Date.now() - 2 * 3600_000),
      updateTime: fmt(Date.now() - 1 * 3600_000)
    },
    {
      id: 2,
      title: '我的体检报告里血脂偏高怎么办',
      kind: 'normal',
      messageCount: 4,
      createTime: fmt(Date.now() - 26 * 3600_000),
      updateTime: fmt(Date.now() - 25 * 3600_000)
    }
  ]
}

export const sessions: MockSession[] = seedSessions()

/**
 * 列表排序（契约 §3.2）：**专属对话恒置顶**，其余按活跃时间倒序。
 * 这是需求「主对话就是第一个对话」的实现——不这么排，专属对话
 * 会被更活跃的普通会话挤下去，"第一个"的语义就不成立。
 */
export function listSessions(): MockSession[] {
  return [...sessions].sort((a, b) => {
    const pa = a.kind === 'portrait' ? 1 : 0
    const pb = b.kind === 'portrait' ? 1 : 0
    if (pa !== pb) return pb - pa
    return b.updateTime.localeCompare(a.updateTime)
  })
}

export function findPortraitSession(): MockSession | null {
  return sessions.find((s) => s.kind === 'portrait') ?? null
}

export function createSession(kind: 'normal' | 'portrait'): MockSession {
  sessionSeq += 1
  const now = fmt(Date.now())
  const s: MockSession = {
    id: sessionSeq,
    title: kind === 'portrait' ? PORTRAIT_SESSION_TITLE : '新对话',
    kind,
    messageCount: 0,
    createTime: now,
    updateTime: now
  }
  sessions.unshift(s)
  return s
}

export function renameSession(id: number, title: string): boolean {
  const s = sessions.find((x) => x.id === id)
  if (!s) return false
  s.title = title
  s.updateTime = fmt(Date.now())
  return true
}

export function removeSession(id: number): boolean {
  const idx = sessions.findIndex((s) => s.id === id)
  if (idx < 0) return false
  sessions.splice(idx, 1)
  delete MESSAGES[id]
  return true
}

/**
 * 落一条问答后前移会话的活跃时间与消息数。
 * 不做这一步，刚答完的会话在列表里显示「0 条消息」——截图与验收都会因此失真，
 * 而这种"明显不对但没人断言"的地方，正是 mock 最容易骗过自己的地方。
 */
export function bumpSession(id: number, delta: number): void {
  const s = sessions.find((x) => x.id === id)
  if (!s) return
  s.messageCount += delta
  s.updateTime = fmt(Date.now())
}

/**
 * 会话消息：**刻意让答案同时引用全局库与个人库**——
 * 这是验证「引用来源标注」的唯一方式，也是本 mock 最关键的夹具。
 */
export const MESSAGES: Record<number, unknown[]> = {
  1: [
    {
      id: 11,
      role: 'user',
      content: '高血压患者日常要注意什么？我自己的体检报告里也有相关信息吗',
      references: [],
      retrieveMode: null,
      latencyMs: null,
      firstTokenMs: null,
      finishStatus: 1,
      isRejected: 0,
      createTime: fmt(Date.now() - 2 * 3600_000)
    },
    {
      id: 12,
      role: 'assistant',
      content:
        '高血压的日常管理主要围绕生活方式干预展开。\n\n' +
        '**一、减少钠盐摄入**\n每人每日食盐摄入量逐步降至 5g 以下，同时增加富含钾的新鲜蔬菜水果。\n\n' +
        '**二、控制体重与规律运动**\nBMI 建议控制在 24 以下；每周 5~7 天中等强度有氧运动，每次 30 分钟。\n\n' +
        '**三、结合你上传的资料**\n你上传的体检报告中记录了近期血压与血脂数值，其结论与指南建议的高危因素一致，建议按报告中医生建议的随访周期复查 [2]。\n\n' +
        '以上为科普信息，不能替代医生的诊断与处方。',
      references: [
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
        },
        {
          chunkId: 8128,
          docId: 12,
          doc: '成人高血压食养指南',
          section: '二、膳食原则',
          digest: '推荐采用低钠高钾膳食模式，每日摄入新鲜蔬菜 300~500g、水果 200~350g。',
          score: 0.653,
          scope: 'global'
        }
      ],
      retrieveMode: 'hybrid',
      latencyMs: 2860,
      firstTokenMs: 742,
      finishStatus: 1,
      isRejected: 0,
      createTime: fmt(Date.now() - 2 * 3600_000 + 4000)
    }
  ],
  2: [
    {
      id: 21,
      role: 'user',
      content: '我的体检报告里血脂偏高怎么办',
      references: [],
      retrieveMode: null,
      latencyMs: null,
      firstTokenMs: null,
      finishStatus: 1,
      isRejected: 0,
      createTime: fmt(Date.now() - 26 * 3600_000)
    },
    {
      id: 22,
      role: 'assistant',
      content: '血脂偏高需要结合具体指标判断。你的报告中低密度脂蛋白为 3.9mmol/L，属轻度升高，建议先从饮食与运动干预开始。',
      references: [
        {
          chunkId: 88302,
          docId: 1001,
          doc: '我的体检报告 2026',
          section: '二、检查结果',
          digest: '低密度脂蛋白 3.9mmol/L，医生建议 4 周后复查。',
          score: 0.771,
          scope: 'personal'
        }
      ],
      retrieveMode: 'hybrid',
      latencyMs: 2410,
      firstTokenMs: 690,
      finishStatus: 1,
      isRejected: 0,
      createTime: fmt(Date.now() - 26 * 3600_000 + 3500)
    }
  ]
}

/** 消息的初始快照：resetAll 要连消息一起恢复（删除会话会真的删掉 MESSAGES 里的键） */
const SEED_MESSAGES: Record<number, unknown[]> = JSON.parse(JSON.stringify(MESSAGES))

/* ---------------- 登录态 ---------------- */

export const MOCK_USERS: Record<string, { password: string; role: 'USER' | 'ADMIN'; id: number }> = {
  admin: { password: 'Admin@123', role: 'ADMIN', id: 1 },
  renhao: { password: 'abc123456', role: 'USER', id: 5 }
}

export function userInfo(username: string) {
  const u = MOCK_USERS[username] ?? MOCK_USERS.renhao
  return {
    id: u.id,
    username,
    nickname: username === 'admin' ? '系统管理员' : '任浩',
    email: null,
    avatar: null,
    role: u.role,
    createTime: '2026-09-01 09:00:00'
  }
}

/* ============================================================
 * 健康画像（契约 #53–#58）
 * ============================================================ */

export interface MockPortrait {
  gender: string | null
  birthYear: number | null
  heightCm: number | null
  weightKg: number | null
  conditions: string[]
  allergies: string
  medications: string
  exercise: string | null
  sleepHours: number | null
  smoking: string | null
  drinking: string | null
  diet: string[]
  goals: string[]
  note: string
}

function blankPortrait(): MockPortrait {
  return {
    gender: null,
    birthYear: null,
    heightCm: null,
    weightKg: null,
    conditions: [],
    allergies: '',
    medications: '',
    exercise: null,
    sleepHours: null,
    smoking: null,
    drinking: null,
    diet: [],
    goals: [],
    note: ''
  }
}

/**
 * 初始为**未填写**，而不是预置一份假画像。两个理由：
 *   ① 这才是新用户的真实起点；
 *   ② 「画像为空 → 新建会话时引导去填」这条分支只有在空态下才走得到，
 *      预置假数据会让这条路径永远测不到（验收就会漏掉它）。
 */
export const portrait: { data: MockPortrait | null; updateTime: string | null } = {
  data: null,
  updateTime: null
}

/** 计入完善度的字段总数（与契约 §5.2 的 14 项一致） */
export const PORTRAIT_FIELD_TOTAL = 14

function isBlank(v: unknown): boolean {
  if (v == null) return true
  if (typeof v === 'string') return v.trim() === ''
  if (Array.isArray(v)) return v.length === 0
  return false
}

export function portraitFilledCount(p: MockPortrait): number {
  return Object.values(p).filter((v) => !isBlank(v)).length
}

/** #53 响应：未填写时 exists=false，其余字段为 null / 空数组（不是 404） */
export function portraitView(): Record<string, unknown> {
  const p = portrait.data ?? blankPortrait()
  const filled = portrait.data ? portraitFilledCount(p) : 0
  return {
    ...p,
    exists: portrait.data !== null,
    filledCount: filled,
    totalCount: PORTRAIT_FIELD_TOTAL,
    completeness: Math.round((filled / PORTRAIT_FIELD_TOTAL) * 100),
    updateTime: portrait.updateTime
  }
}

/** #54 保存（全量覆盖，UPSERT 语义） */
export function savePortrait(body: Partial<MockPortrait>): void {
  const base = portrait.data ?? blankPortrait()
  portrait.data = {
    ...base,
    ...body,
    // 数组字段做一次防御性拷贝：请求体里的数组不该与内部状态共享引用
    conditions: Array.isArray(body.conditions) ? [...body.conditions] : base.conditions,
    diet: Array.isArray(body.diet) ? [...body.diet] : base.diet,
    goals: Array.isArray(body.goals) ? [...body.goals] : base.goals
  }
  portrait.updateTime = fmt(Date.now())
}

/** #55 清空（物理删除语义；**不**级联删除专属对话） */
export function clearPortrait(): void {
  portrait.data = null
  portrait.updateTime = null
}

/** 画像是否有内容 —— 决定专属对话能否创建（2008）以及回答是否真的注入了画像 */
export function portraitHasContent(): boolean {
  return portrait.data !== null && portraitFilledCount(portrait.data) > 0
}

/** #56 字段字典：中文标签的唯一来源，前端不得硬编码 */
export const PORTRAIT_OPTIONS = {  genders: [
    { value: 'male', label: '男' },
    { value: 'female', label: '女' },
    { value: 'other', label: '其他' }
  ],
  conditions: [
    { value: 'hypertension', label: '高血压' },
    { value: 'diabetes', label: '糖尿病' },
    { value: 'hyperlipidemia', label: '高血脂' },
    { value: 'hyperuricemia', label: '高尿酸 / 痛风' },
    { value: 'chd', label: '冠心病' },
    { value: 'asthma', label: '哮喘' },
    { value: 'thyroid', label: '甲状腺疾病' },
    { value: 'fatty_liver', label: '脂肪肝' }
  ],
  exercise: [
    { value: 'never', label: '几乎不运动' },
    { value: 'rare', label: '偶尔动一动' },
    { value: 'weekly1_2', label: '每周 1~2 次' },
    { value: 'weekly3_5', label: '每周 3~5 次' },
    { value: 'daily', label: '几乎每天' }
  ],
  smoking: [
    { value: 'never', label: '从不' },
    { value: 'quit', label: '已戒' },
    { value: 'light', label: '偶尔' },
    { value: 'heavy', label: '每天' }
  ],
  drinking: [
    { value: 'never', label: '从不' },
    { value: 'rarely', label: '很少' },
    { value: 'social', label: '社交场合' },
    { value: 'frequent', label: '经常' }
  ],
  diet: [
    { value: 'low_salt', label: '低盐' },
    { value: 'low_sugar', label: '低糖' },
    { value: 'low_fat', label: '低脂' },
    { value: 'high_protein', label: '高蛋白' },
    { value: 'vegetarian', label: '素食' },
    { value: 'irregular', label: '三餐不规律' }
  ],
  goals: [
    { value: 'lose_weight', label: '减重' },
    { value: 'gain_muscle', label: '增肌' },
    { value: 'control_bp', label: '控血压' },
    { value: 'control_sugar', label: '控血糖' },
    { value: 'control_lipid', label: '降血脂' },
    { value: 'better_sleep', label: '改善睡眠' },
    { value: 'quit_smoking', label: '戒烟' },
    { value: 'keep_fit', label: '保持健康' }
  ]
}

/* ============================================================
 * 重置（仅 dev mock）
 * ============================================================ */

/**
 * 把全部内存态恢复到"刚启动"的样子。
 *
 * 存在的理由：**验收脚本必须可以反复运行**。没有它，第二次跑的时候起点已经被
 * 上一遍改动过（画像被填了、专属对话已存在），断言会随机失败——而且失败现象
 * 看起来像功能缺陷，比没有测试更糟（"测试骗人"比"测试失败"危险得多）。
 *
 * 真实后端没有这个端点，它只活在 dev mock 里。
 */
export function resetAll(): void {
  personalDocs.length = 0
  personalDocs.push(...seedDocs())
  docSeq = 2000
  chunkSeq = 88000

  sessions.length = 0
  sessions.push(...seedSessions())
  sessionSeq = 100

  for (const key of Object.keys(MESSAGES)) delete MESSAGES[Number(key)]
  Object.assign(MESSAGES, JSON.parse(JSON.stringify(SEED_MESSAGES)) as Record<number, unknown[]>)

  portrait.data = null
  portrait.updateTime = null
}


