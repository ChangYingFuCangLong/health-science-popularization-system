/* ============================================================
 * 管理端页面检查（真实浏览器）
 *
 * 用法：npm run test:admin
 *
 * 背景：后端 M8（管理端接口）尚未实现，接口当前返回业务码 404。
 * 因此本脚本验证两件事：
 *   1. 接口缺失时页面【优雅降级】为空态说明，而不是刷错误弹窗
 *   2. 有数据时表格能正常渲染（用拦截注入样本数据来验）
 *
 * 关于 ADMIN 角色：不改动数据库，也不用真实管理员账号，
 * 而是拦截登录响应把 role 改成 ADMIN —— 只影响本次浏览器会话。
 * ============================================================ */

import { existsSync, unlinkSync, writeFileSync } from 'node:fs'
import { chromium } from 'playwright-core'

const BASE = process.env.SMOKE_BASE ?? 'http://localhost:5173'
const USER = process.env.SMOKE_USER ?? 'qian_e2e'
const PASS = process.env.SMOKE_PASSWORD ?? 'test123456'
/**
 * 真实管理员账号（后端按 JWT 中的角色鉴权，/api/admin/** 需 ADMIN）。
 * 用环境变量传入，避免把口令写进仓库：
 *     SMOKE_ADMIN_USER=admin SMOKE_ADMIN_PASSWORD=xxx npm run test:admin
 */
const ADMIN_USER = process.env.SMOKE_ADMIN_USER ?? ''
const ADMIN_PASS = process.env.SMOKE_ADMIN_PASSWORD ?? ''

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
]

function findBrowser() {
  const fromEnv = process.env.SMOKE_CHROME
  if (fromEnv && existsSync(fromEnv)) return fromEnv
  for (const p of CHROME_CANDIDATES) {
    if (existsSync(p)) return p
  }
  throw new Error('未找到 Chrome / Edge')
}

const problems = []
const notes = []
function check(label, ok, detail = '') {
  console.log(`${ok ? '✅' : '❌'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) problems.push(label)
}

/** 分页包装：与后端真实结构一致 */
function pageResult(records) {
  return {
    code: 200,
    message: 'success',
    data: { total: records.length, pages: 1, current: 1, size: 20, records }
  }
}

const browser = await chromium.launch({ executablePath: findBrowser(), headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

const consoleErrors = []
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => consoleErrors.push(`未捕获：${e.message}`))

async function doLogin(user, pass) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  await page.waitForTimeout(1200)
  await page.locator('input[type="text"]').first().fill(user)
  await page.locator('input[type="password"]').first().fill(pass)
  await page.locator('button[type="submit"]').first().click()
  await page.waitForTimeout(2500)
}

try {
  /* ---------- 0. 非管理员不得进入管理端（前端守卫） ---------- */
  await doLogin(USER, PASS)
  await page.goto(`${BASE}/admin/dashboard`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  // 刷新后 userInfo 需异步补齐（守卫刻意不阻塞），因此轮询等拦截生效
  let url = page.url()
  for (let i = 0; i < 16; i++) {
    url = page.url()
    if (!url.includes('/admin')) break
    await page.waitForTimeout(400)
  }
  const guardText = (await page.locator('body').innerText().catch(() => '')).replace(/\n/g, ' ')
  check(
    '非管理员访问 /admin 被守卫拦回用户端',
    !url.includes('/admin'),
    `${url} :: ${guardText.slice(0, 80)}`
  )

  /* ---------- 1. 以管理员身份登录 ---------- */
  if (!ADMIN_USER || !ADMIN_PASS) {
    notes.push(
      '未提供 SMOKE_ADMIN_USER / SMOKE_ADMIN_PASSWORD，跳过管理端数据面检查。' +
        '（后端按 JWT 角色鉴权，伪造前端角色无法通过 /api/admin/** 的 403）'
    )
    throw new Error('SKIP')
  }
  await page.evaluate(() => localStorage.clear())
  await doLogin(ADMIN_USER, ADMIN_PASS)

  await page.goto(`${BASE}/admin/dashboard`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  await page.waitForTimeout(2000)
  check('管理员可进入管理端', page.url().includes('/admin'), page.url())

  /* ---------- 1. 各页面在接口缺失时优雅降级 ---------- */
  const PAGES = [
    ['/admin/dashboard', '数据看板'],
    ['/admin/documents', '语料管理'],
    ['/admin/logs', '问答日志'],
    ['/admin/feedbacks', '反馈管理'],
    ['/admin/users', '用户管理'],
    ['/admin/eval/dataset', '评测集管理'],
    ['/admin/eval/results', '实验结果']
  ]

  // 轮询等待条件成立（dev 模式首次访问会触发 EP 组件按需编译，固定等待不可靠）
  async function waitUntil(predicate, timeout = 15_000) {
    const started = Date.now()
    let text = ''
    while (Date.now() - started < timeout) {
      text = await page.locator('body').innerText().catch(() => '')
      if (predicate(text)) return text
      await page.waitForTimeout(400)
    }
    return text
  }

  for (const [path, title] of PAGES) {
    await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
    // 必须等「加载中」消失再断言，否则读到的是中间态
    const text = await waitUntil((t) => t.includes(title) && !t.includes('加载中'))
    const hasTitle = text.includes(title)
    // M8 已实现：不应再出现「尚未实现」的降级态；允许「暂无数据」（库里确实可能为空）
    const ok = text.includes('尚未实现') === false
    check(
      `${title}：渲染正常（未落入「接口未实现」降级态）`,
      hasTitle && ok,
      text.replace(/\n/g, ' ').slice(0, 70)
    )
  }

  /* ---------- 2. 真实数据：表格应渲染出行 ---------- */
  // 用真实后端数据（不再注入样本）：只要渲染出表格行，就说明
  // 分页结构与字段名对得上；字段名不符会表现为空列或空表。
  for (const [path, title] of [
    ['/admin/users', '用户管理'],
    ['/admin/documents', '语料管理'],
    ['/admin/logs', '问答日志']
  ]) {
    await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
    const text = await waitUntil((t) => t.includes(title) && !t.includes('加载中'))
    const rows = await page.locator('.el-table__row').count().catch(() => 0)
    const hasTable = await page.locator('.el-table').count().catch(() => 0)
    check(
      `${title}：真实数据渲染出表格行`,
      hasTable > 0 && rows > 0,
      `表格=${hasTable} 行数=${rows}${text.includes('暂无') ? '（接口返回空）' : ''}`
    )
  }

  /* ---------- 2.1 F1 回归：语料管理状态列必须是后端枚举语义 ----------
   * 契约（02 §6.1）：0 待处理 / 1 处理中 / 2 已完成 / 3 失败。
   * 曾因前端枚举错位导致全库文档显示「失败」而 e2e 未察觉——
   * 存在性断言（有行即过）对这种静默错误免疫，必须断言状态取值本身。 */
  {
    await page.goto(`${BASE}/admin/documents`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
    const text = await waitUntil((t) => t.includes('语料管理') && !t.includes('加载中'))
    const hasValidStatus = ['已完成', '处理中', '待处理'].some((s) => text.includes(s))
    check(
      '语料管理：状态列出现有效状态（F1 回归）',
      hasValidStatus,
      hasValidStatus ? '' : '未出现 已完成/处理中/待处理 任何一种状态'
    )
  }

  /* ---------- 2.2 上传链路 e2e（复测报告建议 1）----------
   * 构造小 md → setInputFiles → 轮询列表出现新行且状态最终「已完成」→ 删除清理不污染库。
   * 前提：语料列表按创建时间倒序（新文档落在第一页）；解析为异步流水线，上限 90s。 */
  {
    const stamp = Date.now()
    const title = `tmp-upload-${stamp}`   // 上传不传 title，后端缺省取文件名去扩展名（契约 §6.2）
    const mdPath = `tests/e2e/tmp-upload-${Date.now()}.md`
    writeFileSync(
      mdPath,
      `# ${title}\n\n这是 e2e 上传链路的临时测试文档，验证完成后会被删除。\n\n` +
        '- 要点一：内容需足够被解析分块\n- 要点二：健康科普测试语料，血压的日常监测\n'
    )
    try {
      // 轻断言（复测报告建议 4）：上传入口存在且未回归为禁用态。
      // 原生文件选择器无法在 headless 下断言"弹出面板"，input 可用性是等价代理。
      const fileInput = page.locator('input[type="file"]').first()
      const inputReady = (await fileInput.count()) > 0 && (await fileInput.isEnabled())
      check('语料管理：上传入口存在且可用', inputReady)

      await fileInput.setInputFiles(mdPath)
      let done = false
      let failed = false
      for (let i = 0; i < 45 && !done && !failed; i++) {
        await page.waitForTimeout(2000)
        const row = page.locator('.el-table__row', { hasText: title }).first()
        if ((await row.count().catch(() => 0)) === 0) continue
        const rowText = await row.innerText().catch(() => '')
        if (rowText.includes('已完成')) done = true
        else if (rowText.includes('失败')) failed = true
      }
      const finalRow = page.locator('.el-table__row', { hasText: title }).first()
      const rowText = (await finalRow.innerText().catch(() => '')).replace(/\n/g, ' ')
      check(
        '上传文档完成解析并进入列表（F2 e2e）',
        done,
        `done=${done} failed=${failed} :: ${rowText.slice(0, 60)}`
      )

      // 清理：删除该文档（含 ElMessageBox 二次确认），避免污染演示库
      if (done) {
        await finalRow.getByText('删除', { exact: true }).click()
        // confirm 主按钮取 .el-button--primary，不受 element-plus 语言配置影响
        await page.locator('.el-message-box__btns button.el-button--primary').first().click()
        await page.waitForTimeout(1500)
        const gone = (await page.locator('.el-table__row', { hasText: title }).count()) === 0
        check('上传的测试文档已删除（不污染演示库）', gone)
      }
    } finally {
      try {
        unlinkSync(mdPath)
      } catch {
        /* 临时文件已不存在则忽略 */
      }
    }
  }

  /* ---------- 3. 控制台干净 ---------- */
  check('无控制台错误', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))
} catch (err) {
  if (err instanceof Error && err.message === 'SKIP') {
    // 未提供管理员凭据：第 0 项（守卫）已验，其余跳过，不算失败
  } else {
    problems.push(`执行异常：${err instanceof Error ? err.message : String(err)}`)
    console.error(err)
  }
} finally {
  await browser.close()
}

console.log('\n———— 结果 ————')
for (const n of notes) console.log(`ℹ️  ${n}`)
if (problems.length === 0) {
  console.log('🎉 全部通过')
} else {
  console.log(`⚠️  ${problems.length} 项未通过：`)
  for (const p of problems) console.log(`   - ${p}`)
  process.exitCode = 1
}
