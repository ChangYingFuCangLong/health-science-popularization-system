/* ============================================================
 * 前端冒烟检查（真实浏览器）
 *
 * 用法（需后端与 dev server 均已启动）：
 *     npm run test:smoke
 *
 * 说明：
 *   - 用 playwright-core + 本机已安装的 Chrome/Edge，不下载 Chromium
 *   - 走真实流程：打开 → 登录 → 提问 → 观察流式渲染 → 检查控制台错误
 *   - 退出码非 0 表示有问题
 * ============================================================ */

import { existsSync } from 'node:fs'
import { chromium } from 'playwright-core'

const BASE = process.env.SMOKE_BASE ?? 'http://localhost:5173'
const USER = process.env.SMOKE_USER ?? 'qian_e2e'
const PASS = process.env.SMOKE_PASSWORD ?? 'test123456'

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
  throw new Error('未找到 Chrome / Edge，请设置 SMOKE_CHROME 指向浏览器可执行文件')
}

const problems = []
const notes = []

function check(label, ok, detail = '') {
  const line = `${ok ? '✅' : '❌'} ${label}${detail ? ` — ${detail}` : ''}`
  console.log(line)
  if (!ok) problems.push(label)
}

const browser = await chromium.launch({ executablePath: findBrowser(), headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })

const consoleErrors = []
const pageErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', (err) => pageErrors.push(err.message))

try {
  /* ---------- 1. 打开应用 ---------- */
  await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30_000 })
  await page.waitForTimeout(1500)

  const url1 = page.url()
  check('未登录时自动跳登录页', url1.includes('/login'), url1)

  const title = await page.title()
  check('页面标题正确', title.includes('健康科普'), title)

  /* ---------- 2. 登录 ---------- */
  const userInput = page.locator('input[type="text"]').first()
  const pwdInput = page.locator('input[type="password"]').first()
  await userInput.fill(USER)
  await pwdInput.fill(PASS)
  await page.locator('button[type="submit"]').first().click()

  await page.waitForTimeout(2500)
  const url2 = page.url()
  check('登录后进入对话页', url2.includes('/chat'), url2)

  /* ---------- 3. 顶栏与侧栏 ---------- */
  const headerText = await page.locator('header').first().innerText().catch(() => '')
  check('顶栏渲染（含导航）', headerText.includes('健康科普助手'), headerText.replace(/\n/g, ' | '))

  const hasNewChat = await page.getByText('新建对话').first().isVisible().catch(() => false)
  check('会话侧栏渲染', hasNewChat)

  /* ---------- 4. 免责声明常驻 ---------- */
  const bodyText = await page.locator('body').innerText()
  check('免责声明常驻', bodyText.includes('不能替代医生的诊断与处方'))

  /* ---------- 5. 发送提问，观察流式渲染 ---------- */
  const textarea = page.locator('textarea').first()
  await textarea.fill('感冒了怎么办')
  await textarea.press('Enter')

  // 生成中应立刻切到「停止」，并出现助手占位气泡
  await page.waitForTimeout(600)
  const streamingNow = await page
    .getByRole('button', { name: '停止' })
    .first()
    .isVisible()
    .catch(() => false)
  check('发送后进入生成态（按钮变为「停止」）', streamingNow)

  const samples = []
  let sawStop = false
  // 真实 LLM 的输出比 Mock 慢得多：轮询而不是固定等待，
  // 最多等 60s，或直到生成结束（「停止」变回「发送」）
  for (let i = 0; i < 120; i++) {
    await page.waitForTimeout(500)
    const arts = page.locator('article')
    const n = await arts.count().catch(() => 0)
    const last = n > 0 ? await arts.nth(n - 1).innerText().catch(() => '') : ''
    samples.push(last.length)

    const stopVisible = await page
      .getByRole('button', { name: '停止' })
      .first()
      .isVisible()
      .catch(() => false)
    if (stopVisible) sawStop = true
    if (sawStop && !stopVisible && last.length > 0) break
  }

  const grew = samples[samples.length - 1] > samples[0]
  check(
    '流式渲染（回答内容随时间增长）',
    grew,
    `采样首=${samples[0]} 末=${samples[samples.length - 1]}`
  )

  const articles = page.locator('article')
  const count = await articles.count()
  check('消息气泡渲染（用户 + 助手）', count >= 2, `气泡数=${count}`)

  const answerText = count >= 2 ? await articles.nth(count - 1).innerText() : ''
  check('收到完整回答', answerText.trim().length > 30, `长度=${answerText.trim().length}`)

  /* ---------- 6. 生成结束后恢复「发送」 ---------- */
  const backToSend = await page
    .getByRole('button', { name: '发送' })
    .first()
    .isVisible()
    .catch(() => false)
  check('生成结束后恢复为「发送」按钮', backToSend)

  /* ---------- 7. 控制台干净 ---------- */
  const realConsoleErrors = consoleErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('Failed to load resource: the server responded with a status of 404')
  )
  check('无控制台错误', realConsoleErrors.length === 0, realConsoleErrors.slice(0, 3).join(' ;; '))
  check('无未捕获异常', pageErrors.length === 0, pageErrors.slice(0, 3).join(' ;; '))

  /* ---------- 8. 截图 ---------- */
  await page.screenshot({ path: 'tests/e2e/smoke-chat.png', fullPage: false })
  notes.push('截图已保存：tests/e2e/smoke-chat.png')

  /* ---------- 8.1 会话列表与切换（验证历史消息加载） ---------- */
  const sessionBtns = page.locator('nav[aria-label="会话列表"] button')
  const sessionCount = await sessionBtns.count()
  check('侧栏会话列表有数据', sessionCount > 0, `会话数=${sessionCount}`)

  if (sessionCount > 0) {
    await sessionBtns.first().click()
    await page.waitForTimeout(2500)
    const loaded = await page.locator('article').count()
    const firstMsg = loaded > 0 ? await page.locator('article').first().innerText() : ''
    check(
      '切换会话后加载历史消息',
      loaded >= 2 && firstMsg.trim().length > 0,
      `气泡数=${loaded} 首条="${firstMsg.slice(0, 20)}"`
    )
  }

  /* ---------- 9. 知识浏览：后端已实现（M5），应渲染出真实数据 ---------- */
  await page.goto(`${BASE}/knowledge`, { waitUntil: 'domcontentloaded' })
  // 首次加载要拉分类 + 文章列表，等待分类芯片出现，不再用固定等待赌时序
  const categoryChip = page.locator('button').filter({ hasText: '慢性病管理' }).first()
  await categoryChip.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {})
  const kText = await page.locator('body').innerText()
  // 后端已提供 /api/knowledge/*：应出现分类或文章，且不显示降级态
  check(
    '知识浏览渲染出真实数据（非降级空态）',
    !kText.includes('M5') &&
      !kText.includes('接口尚未就绪') &&
      (await categoryChip.count()) > 0,
    kText.slice(0, 80).replace(/\n/g, ' | ')
  )
  // 点开第一篇文章（卡片第二行带「共 N 段」），验证详情面板（正文由 chunk 拼接还原）
  const firstArticle = page.locator('button').filter({ hasText: '共 ' }).first()
  if (await firstArticle.count()) {
    await firstArticle.click()
    await page.waitForTimeout(2500)
    const dText = await page.locator('body').innerText()
    check(
      '知识详情面板能打开并显示正文',
      dText.includes('返回列表') && dText.length > 500 && !dText.includes('加载失败'),
      `正文长度=${dText.length}`
    )
  } else {
    notes.push('知识浏览：未找到文章卡片，跳过详情检查')
  }

  /* ---------- 10. 退出登录 ---------- */
  await page.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  await page.getByRole('button', { name: '退出' }).first().click()
  await page.waitForTimeout(2000)
  check('退出登录后回到登录页', page.url().includes('/login'), page.url())
} catch (err) {
  problems.push(`执行异常：${err instanceof Error ? err.message : String(err)}`)
  console.error(err)
} finally {
  await browser.close()
}

console.log('\n———— 结果 ————')
if (notes.length) notes.forEach((n) => console.log(`ℹ️  ${n}`))
if (problems.length === 0) {
  console.log('🎉 全部通过')
  process.exit(0)
} else {
  console.log(`⚠️  ${problems.length} 项未通过：`)
  problems.forEach((p) => console.log(`   - ${p}`))
  process.exit(1)
}
