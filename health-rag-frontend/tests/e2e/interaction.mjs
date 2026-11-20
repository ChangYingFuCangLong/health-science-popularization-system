/* ============================================================
 * 交互行为检查（真实浏览器）
 *
 * 用法（需后端、数据库、dev server 均已启动）：
 *     npm run test:interaction
 *
 * 与 smoke.mjs 的分工：
 *   smoke.mjs       —— 「能不能用」：登录、流式渲染、会话、知识浏览
 *   本文件          —— 「用起来对不对」：停止生成、自动滚动、焦点归位
 *
 * 这几项目前只能靠真实浏览器验证（Node 侧单测覆盖不到 DOM 行为），
 * 且都对应设计文档里的明确约定：
 *   - 停止生成：AbortError 必须静默，不得弹报错（前端设计.md §6.2 注⑥）
 *   - 中断保留：已生成内容保留 + 标记「已中断」（ADR-015）
 *   - 自动滚动：仅当用户已在底部附近时才跟随（前端设计.md §5 交互）
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
  console.log(`${ok ? '✅' : '❌'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) problems.push(label)
}

/**
 * 消息滚动容器。
 * ⚠️ 必须用 `:has(article)` 限定——会话侧栏（SessionSidebar）同样带
 * `overflow-y-auto` 且在 DOM 中更靠前，用 `.first()` 会选中侧栏，
 * 导致滚动相关断言测的是错误元素（曾因此出现「假通过」）。
 */
const SCROLLER = 'div[class*="overflow-y-auto"]:has(article)'

const browser = await chromium.launch({ executablePath: findBrowser(), headless: true })
// 视口刻意压矮：内容更容易溢出，滚动相关的断言才有意义
const page = await browser.newPage({ viewport: { width: 1280, height: 700 } })

const consoleErrors = []
const pageErrors = []
const httpErrors = []
page.on('console', (msg) => {
  // 带上来源 URL：控制台只说 "Failed to load resource"，看不出是哪个请求
  if (msg.type() === 'error') {
    const loc = msg.location?.()?.url
    consoleErrors.push(loc ? `${msg.text()} @ ${loc}` : msg.text())
  }
})
page.on('pageerror', (err) => pageErrors.push(err.message))
// 记录非 2xx 响应：控制台只报 "Failed to load resource"，看不出是哪个 URL
page.on('response', (res) => {
  if (res.status() >= 400) httpErrors.push(`${res.status()} ${res.url()}`)
})

try {
  /* ---------- 登录 ---------- */
  await page.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded', timeout: 30_000 })
  await page.waitForTimeout(1500)
  if (page.url().includes('/login')) {
    await page.locator('input[type="text"]').first().fill(USER)
    await page.locator('input[type="password"]').first().fill(PASS)
    await page.locator('button[type="submit"]').first().click()
    await page.waitForTimeout(2500)
  }
  check('已进入对话页', page.url().includes('/chat'), page.url())

  // 先打开一个已有会话：让消息区有足够内容可滚动，
  // 否则滚动相关的断言会因「容器根本不可滚动」而失去意义（会假通过）
  const historySession = page.locator('nav[aria-label="会话列表"] button').first()
  if (await historySession.count()) {
    await historySession.click()
    await page.waitForTimeout(2500)
  }

  const textarea = page.locator('textarea').first()
  const scroller = page.locator(SCROLLER).first()

  /** 最后一条消息（助手）的文本 */
  async function lastBubbleText() {
    const arts = page.locator('article')
    const n = await arts.count().catch(() => 0)
    return n > 0 ? await arts.nth(n - 1).innerText().catch(() => '') : ''
  }

  /** 等到助手回答出现指定长度，返回其文本；超时返回当前文本 */
  async function waitForAnswer(minLen, maxMs = 45_000) {
    const started = Date.now()
    let text = ''
    while (Date.now() - started < maxMs) {
      await page.waitForTimeout(500)
      text = await lastBubbleText()
      if (text.trim().length >= minLen) break
    }
    return text
  }

  /* ---------- 1. 发送 → 生成中把容器滚到顶，验证「不被拽到底」 ---------- */
  // 问题刻意问得长，保证生成过程持续足够久（否则来不及做滚动与停止的观察）
  await textarea.fill('请详细介绍高血压的预防方法，包括饮食、运动、作息和用药注意事项')
  await textarea.press('Enter')
  await page.waitForTimeout(1200)

  const answer1 = await waitForAnswer(200)
  check('开始生成回答', answer1.trim().length >= 200, `长度=${answer1.trim().length}`)

  // 前提：容器必须真的可滚动，否则 scrollTop 恒为 0，这条断言会「假通过」
  const scrollable = await scroller.evaluate((el) => el.scrollHeight - el.clientHeight > 200)
  if (!scrollable) {
    notes.push('消息区内容不足以产生滚动，自动滚动相关断言已跳过（否则会假通过）')
  } else {
    // 用户主动往上翻 → 新内容到达时不应把视野拽回底部
    await scroller.evaluate((el) => {
      el.scrollTop = 0
    })
    await page.waitForTimeout(2000)
    const topAfter = await scroller.evaluate((el) => el.scrollTop)
    check(
      '生成中用户上翻后不被强制拉回底部',
      topAfter < 200,
      `上翻后 2s 的 scrollTop=${topAfter}`
    )
  }

  /* ---------- 2. 停止生成（AbortError 静默 + 中断保留） ---------- */
  const beforeStop = (await lastBubbleText()).trim()
  const stopVisible = await page
    .getByRole('button', { name: '停止' })
    .first()
    .isVisible()
    .catch(() => false)
  if (!stopVisible) {
    notes.push('回答在观察窗口内已生成完毕，「停止生成」相关断言本次未覆盖')
  } else {
    await page.getByRole('button', { name: '停止' }).first().click()
  }
  await page.waitForTimeout(2500)

  const afterStop = (await lastBubbleText()).trim()
  if (stopVisible) {
    check('停止后内容保留（ADR-015）', afterStop.length >= beforeStop.length * 0.8,
      `停止前=${beforeStop.length} 停止后=${afterStop.length}`)
    check('停止后标记「生成已中断」', afterStop.includes('生成已中断'), afterStop.slice(-30))
  }

  const backToSend = await page
    .getByRole('button', { name: '发送' })
    .first()
    .isVisible()
    .catch(() => false)
  check('停止后按钮恢复为「发送」', backToSend)

  // 关键：用户主动停止不是故障，绝不能弹报错
  if (stopVisible) {
    const toastTexts = await page.locator('[role="status"]').allInnerTexts().catch(() => [])
    const errorToast = toastTexts.filter((t) => /异常|错误|失败|超时/.test(t))
    check(
      '停止生成不弹错误提示（AbortError 静默）',
      errorToast.length === 0,
      errorToast.length ? `出现了：${errorToast.join(' / ')}` : `当前提示数=${toastTexts.length}`
    )
  }

  /* ---------- 3. 停止后焦点回到输入框 ---------- */
  const activeTag = await page.evaluate(() => document.activeElement?.tagName ?? '')
  check('生成结束后焦点回到输入框', activeTag === 'TEXTAREA', `activeElement=${activeTag}`)

  /* ---------- 4. 在底部时自动跟随滚动 ---------- */
  await scroller.evaluate((el) => {
    el.scrollTop = el.scrollHeight
  })
  // 等滚动事件落定：stickToBottom 由 scroll 事件维护，
  // 程序赋值后立即发送会赶在事件之前，导致状态机仍是「上翻」而误报不跟随
  await page.waitForTimeout(300)
  await textarea.fill('请再详细说明高血压患者的一日三餐应该怎么安排')
  await textarea.press('Enter')

  const metrics = () =>
    scroller.evaluate((el) => ({
      top: Math.round(el.scrollTop),
      h: Math.round(el.scrollHeight),
      view: Math.round(el.clientHeight)
    }))
  const m0 = await metrics()
  await waitForAnswer(200, 30_000)
  const m1 = await metrics()
  notes.push(`滚动诊断：发送后=${JSON.stringify(m0)} 生成中=${JSON.stringify(m1)}`)
  const distance = await scroller.evaluate(
    (el) => el.scrollHeight - el.scrollTop - el.clientHeight
  )
  if (scrollable) {
    // 生成过程中持续贴底：距底部距离应保持很小
    check('在底部时自动跟随新内容', distance < 150, `距底部=${distance}px`)
  } else {
    notes.push(`容器不可滚动，跳过「自动跟随」断言（距底部=${distance}px）`)
  }

  // 尽早停掉，避免无谓消耗真实 LLM 额度
  const stopBtn = page.getByRole('button', { name: '停止' }).first()
  if (await stopBtn.isVisible().catch(() => false)) {
    await stopBtn.click()
    await page.waitForTimeout(1500)
  }

  /* ---------- 5. 控制台干净 ---------- */
  check(
    '无控制台错误',
    consoleErrors.length === 0,
    consoleErrors.length ? `共 ${consoleErrors.length} 条，样例：${consoleErrors[0]}` : ''
  )
  check('无未捕获异常', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '))
  if (httpErrors.length) {
    notes.push(`非 2xx 响应 ${httpErrors.length} 条（需确认是否为预期）：${[...new Set(httpErrors)].slice(0, 4).join(' | ')}`)
  }
} catch (err) {
  problems.push(`执行异常：${err instanceof Error ? err.message : String(err)}`)
  console.error(err)
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
