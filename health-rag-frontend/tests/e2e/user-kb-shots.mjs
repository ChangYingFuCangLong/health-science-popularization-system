/* ============================================================
 * 用户个人知识库 · 契约验收（真实浏览器 + 真实 HTTP）
 *
 * 用法：
 *     npm run dev            # 另开终端，mock 由 vite 插件提供
 *     node tests/e2e/user-kb-shots.mjs
 *
 * 与 mobile-shots.mjs 的关键区别（这一点决定了本套脚本的验收价值）：
 *   本套**不做** playwright 浏览器层 route 拦截 —— /api/** 全部走
 *   vite 插件的 mock 中间件，即走了一遍真实的 fetch → HTTP → 契约解析链路。
 *   代价是依赖 dev server 在跑；收益是「前端 ↔ 契约」的对接真的被验证过，
 *   而不是在浏览器里自说自话。
 *
 * 覆盖：Tab 形态与既有权威库回归、配额仪表、上传、进度轮询、
 *       失败态、分块预览（含从引用卡片带高亮进入）、引用来源 scope 标注、
 *       检索范围开关、删除两步确认、375×812 手机视口。
 *
 * 截图落到仓库内 tests/e2e/screenshots/user-kb（可用 SHOT_DIR 覆盖）。
 * ============================================================ */

import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { chromium } from 'playwright-core'

const BASE = process.env.SHOT_BASE ?? 'http://localhost:5173'
const OUT_DIR = process.env.SHOT_DIR ?? 'tests/e2e/screenshots/user-kb'

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
]

function findBrowser() {
  const fromEnv = process.env.SMOKE_CHROME
  if (fromEnv && existsSync(fromEnv)) return fromEnv
  for (const p of CHROME_CANDIDATES) if (existsSync(p)) return p
  throw new Error('未找到 Chrome / Edge，请设置 SMOKE_CHROME')
}

if (!existsSync(OUT_DIR)) mkdirSync(OUT_DIR, { recursive: true })

/* 上传夹具 */
const UPLOAD_FIXTURE = join(tmpdir(), 'user-kb-fixture.md')
writeFileSync(
  UPLOAD_FIXTURE,
  '# 我的运动计划\n\n每周三次有氧，每次 30 分钟。\n\n## 注意事项\n\n避免空腹运动。\n',
  'utf8'
)

const results = []
let failed = 0

function check(name, ok, detail) {
  results.push({ name, ok, detail })
  if (!ok) failed += 1
  const mark = ok ? '\u001b[32m✓\u001b[0m' : '\u001b[31m✗\u001b[0m'
  console.log(`  ${mark} ${name}${detail ? `  \u001b[2m(${detail})\u001b[0m` : ''}`)
}

const browser = await chromium.launch({ executablePath: findBrowser(), headless: true })

/* ============================================================
 * 桌面视口会话
 * ============================================================ */
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

const consoleErrors = []
page.on('pageerror', (e) => consoleErrors.push(`[pageerror] ${e.message}`))
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(`[console] ${m.text()}`)
})

async function waitApp(p = page) {
  await p.waitForFunction(() => (document.querySelector('#app')?.children.length ?? 0) > 0, null, {
    timeout: 60_000
  })
  await p.evaluate(() => document.fonts.ready)
  await p.waitForTimeout(600)
}

const shots = []
async function shot(name) {
  await page.waitForTimeout(400)
  const file = `${OUT_DIR}/${name}.png`
  await page.screenshot({ path: file, fullPage: false })
  shots.push(file)
  console.log(`   📸 ${name}.png`)
}

async function overflowX(p = page) {
  return p.evaluate(() => {
    const de = document.documentElement
    return { scroll: de.scrollWidth, client: de.clientWidth }
  })
}

try {
  /* ---------- 0. 重置 mock 内存态，保证套件可反复运行 ---------- */
  const resetRes = await fetch(`${BASE}/api/__mock/reset`, { method: 'POST' })
  check('mock 内存态已重置', resetRes.ok, `HTTP ${resetRes.status}`)

  /* ---------- 0b. 注入登录态 ---------- */
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await page.evaluate(() => {
    localStorage.setItem('user', JSON.stringify({ token: 'user-kb-check-token' }))
  })

  /* ---------- 1. 权威知识库回归（改造不能破坏既有契约） ---------- */
  await page.goto(`${BASE}/knowledge`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  const tablist = page.locator('[role="tablist"][aria-label="知识来源"]')
  check('知识探索页出现来源 Tab', await tablist.isVisible())
  const tabCount = await tablist.locator('[role="tab"]').count()
  check('Tab 数量为 2', tabCount === 2, `实际 ${tabCount}`)
  const articleCards = page.locator('button:has-text("指南")')
  check('默认 Tab 仍渲染权威语料（回归）', (await articleCards.count()) > 0)
  await shot('08-权威知识库-桌面回归')

  /* ---------- 2. 切到「我的资料」 ---------- */
  await tablist.locator('[role="tab"]', { hasText: '我的资料' }).click()
  await page.waitForTimeout(900)
  check('URL 带 tab=mine', page.url().includes('tab=mine'), page.url())

  const meters = page.locator('section[aria-label="个人库用量"] > div')
  const meterCount = await meters.count()
  check('配额仪表有 3 项（文档/占用/分块）', meterCount === 3, `实际 ${meterCount}`)

  const zone = page.locator('text=选择文件').first()
  check('上传入口存在', await zone.isVisible())

  const cards = page.locator('article.rounded-card')
  const cardCount = await cards.count()
  check('文档卡片已渲染', cardCount >= 5, `实际 ${cardCount}`)

  check('出现「处理中」状态', (await page.locator('text=处理中').count()) > 0)
  check('出现「已索引入库」状态', (await page.locator('text=已索引入库').count()) > 0)
  check('失败态写出原因', (await page.locator('text=未提取到有效文本').count()) > 0)
  check('失败卡片提供重试', (await page.locator('button:has-text("重试")').count()) > 0)
  check('完成卡片提供查看分块', (await page.locator('button:has-text("查看分块")').count()) > 0)

  /* 处理中卡片必须有真实进度读数（不是转圈） */
  const progressText = await page.locator('text=/\\d+ \\/ \\d+/').count()
  check('处理中显示真实进度 done/total', progressText > 0, `${progressText} 处`)

  await shot('01-我的资料-桌面')

  /* ---------- 3. 分块预览 ----------
     按标题定位到具体卡片：列表首位会随上传变化（上传是 unshift），
     依赖顺序会让断言随夹具漂移。 */
  const targetCard = page.locator('article:has-text("我的体检报告 2026")')
  await targetCard.locator('button:has-text("查看分块")').click()
  const sheet = page.locator('[role="dialog"][aria-label*="分块预览"]')
  await sheet.waitFor({ state: 'visible', timeout: 5000 })
  check('分块预览面板可打开', await sheet.isVisible())
  const chunkItems = sheet.locator('li')
  // 面板可见 ≠ 分块已渲染：数据是打开后才异步拉的，这里必须等首条到位再数，
  // 否则会截到一个刚打开、列表还空的瞬间（断言假失败）
  await chunkItems.first().waitFor({ state: 'visible', timeout: 8000 }).catch(() => {})
  const chunkCount = await chunkItems.count()
  check('分块列表已渲染', chunkCount > 0, `${chunkCount} 条`)
  check('分块带章节路径', (await sheet.locator('text=一、基本信息').count()) > 0)
  check('分块面板有关闭按钮', await page.getByRole('button', { name: '关闭分块预览' }).isVisible())
  await shot('02-分块预览')

  await page.keyboard.press('Escape')
  await page.waitForTimeout(500)
  check('ESC 可关闭分块面板', (await sheet.count()) === 0)

  /* ---------- 4. 上传：真实 multipart → mock 受理 → 进入处理中 ---------- */
  const before = await cards.count()
  await page.locator('input[type="file"]').setInputFiles(UPLOAD_FIXTURE)
  // mock 给上传留了 6s 的最短处理窗口，这里取 1.5s 抓「处理中 + 进度条」的中间态
  await page.waitForTimeout(1500)
  const after = await cards.count()
  check('上传后新文档进入列表', after === before + 1, `${before} → ${after}`)

  const fxCard = page.locator('article:has-text("user-kb-fixture")')
  check('新文档进入处理中', (await fxCard.locator('text=处理中').count()) > 0)
  check('新文档显示 done/total 进度', (await fxCard.locator('text=/\\d+ \\/ \\d+/').count()) > 0)
  const fxBar = fxCard.locator('div.bg-brand')
  check('进度条已出现', (await fxBar.count()) > 0)
  await shot('03-上传后-处理中')

  /* ---------- 5. 删除两步确认 ---------- */
  const delBtn = page.locator('button:has-text("删除")').first()
  await delBtn.click()
  await page.waitForTimeout(300)
  check('删除变为两步确认', (await page.locator('button:has-text("确认删除")').count()) > 0)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)

  /* ---------- 6. 对话页：检索范围开关 + 引用来源 scope 标注 ---------- */
  await page.goto(`${BASE}/chat/1`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await page.waitForTimeout(800)

  const scope = page.locator('[role="group"][aria-label="检索范围"]')
  check('对话页出现检索范围开关', await scope.isVisible())
  check('范围开关有 3 档', (await scope.locator('button').count()) === 3)

  const refToggle = page.locator('button:has-text("引用来源")').first()
  await refToggle.click()
  await page.waitForTimeout(500)

  check('引用卡片标注「我的资料」', (await page.locator('span:has-text("我的资料")').count()) > 0)
  check('引用卡片标注「权威知识库」', (await page.locator('span:has-text("权威知识库")').count()) > 0)
  check('个人资料引用改用虚线边框', (await page.locator('button.border-dashed').count()) > 0)
  check('给出来源可靠性提示', (await page.locator('text=未经权威机构审定').count()) > 0)
  check(
    '触发按钮提示含个人资料条数',
    (await page.locator('text=/含 \\d+ 条我的资料/').count()) > 0
  )
  await shot('04-对话页-引用来源范围标注')

  /* ---------- 7. 从引用卡片溯源到分块 ---------- */
  await page.locator('button:has-text("我的体检报告 2026")').first().click()
  await page.waitForTimeout(1600)
  check('溯源跳转到我的资料 Tab', page.url().includes('tab=mine'), page.url())
  check('溯源跳转携带 doc 参数', page.url().includes('doc=1001'), page.url())
  const sheet2 = page.locator('[role="dialog"][aria-label*="分块预览"]')
  check('溯源自动打开分块预览', await sheet2.isVisible())
  await page.waitForTimeout(600)
  check('命中片段被高亮', (await page.locator('mark.hl-flash').count()) > 0)
  await shot('05-引用溯源-高亮定位')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  /* ---------- 8. 清掉入口参数（避免刷新复发） ---------- */
  check('关闭后清掉 doc 参数', !page.url().includes('doc='), page.url())

  const ovDesk = await overflowX()
  check('桌面无横向溢出', ovDesk.scroll <= ovDesk.client + 1, `${ovDesk.scroll}/${ovDesk.client}`)

  /* ============================================================
   * 手机视口（375×812）
   * ============================================================ */
  const mctx = await browser.newContext({
    viewport: { width: 375, height: 812 },
    deviceScaleFactor: 2,
    hasTouch: true,
    isMobile: true
  })
  const mp = await mctx.newPage()
  mp.on('pageerror', (e) => consoleErrors.push(`[mobile pageerror] ${e.message}`))

  await mp.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await mp.waitForFunction(() => (document.querySelector('#app')?.children.length ?? 0) > 0, null, {
    timeout: 60_000
  })
  await mp.evaluate(() => localStorage.setItem('user', JSON.stringify({ token: 'mobile-kb-token' })))

  async function mshot(name) {
    await mp.waitForTimeout(400)
    const file = `${OUT_DIR}/${name}.png`
    await mp.screenshot({ path: file })
    shots.push(file)
    console.log(`   📸 ${name}.png`)
  }

  await mp.goto(`${BASE}/knowledge?tab=mine`, { waitUntil: 'domcontentloaded' })
  await mp.waitForFunction(() => (document.querySelector('#app')?.children.length ?? 0) > 0, null, {
    timeout: 60_000
  })
  await mp.waitForTimeout(1200)

  const ovMine = await overflowX(mp)
  check('手机端我的资料无横向溢出', ovMine.scroll <= ovMine.client + 1, `${ovMine.scroll}/${ovMine.client}`)

  const mTab = mp.locator('[role="tablist"][aria-label="知识来源"] [role="tab"]').first()
  const mTabBox = await mTab.boundingBox()
  check('Tab 触摸高度 ≥44px', (mTabBox?.height ?? 0) >= 44, `${Math.round(mTabBox?.height ?? 0)}px`)
  await mshot('06-我的资料-375')

  const mCard = mp.locator('article:has-text("我的体检报告 2026") button:has-text("查看分块")')
  const mCardBox = await mCard.boundingBox()
  check('手机端操作按钮触摸高度 ≥44px', (mCardBox?.height ?? 0) >= 44, `${Math.round(mCardBox?.height ?? 0)}px`)

  await mCard.click()
  await mp.waitForTimeout(1200)
  const mSheet = mp.locator('[role="dialog"][aria-label*="分块预览"]')
  check('手机端分块面板打开', await mSheet.isVisible())
  // 量的是面板本身，不是铺满全屏的遮罩容器（后者 y 恒为 0，量它等于没量）
  const mPanel = mSheet.locator('> div').nth(1)
  const mPanelBox = await mPanel.boundingBox()
  check(
    '手机端面板贴底（底部抽屉形态）',
    (mPanelBox?.y ?? 0) > 100,
    `y=${Math.round(mPanelBox?.y ?? 0)}`
  )
  await mshot('07-分块预览-375')
  await mp.keyboard.press('Escape')
  await mp.waitForTimeout(400)

  await mp.goto(`${BASE}/chat/1`, { waitUntil: 'domcontentloaded' })
  await mp.waitForTimeout(1500)
  const mScope = mp.locator('[role="group"][aria-label="检索范围"]')
  check('手机端检索范围开关可见', await mScope.isVisible())
  const mScopeBtn = mScope.locator('button').first()
  const mScopeBox = await mScopeBtn.boundingBox()
  check(
    '范围按钮触摸高度 ≥40px',
    (mScopeBox?.height ?? 0) >= 40,
    `${Math.round(mScopeBox?.height ?? 0)}px`
  )
  const ovChat = await overflowX(mp)
  check('手机端对话页无横向溢出', ovChat.scroll <= ovChat.client + 1, `${ovChat.scroll}/${ovChat.client}`)
  await mshot('09-对话页-范围开关-375')

  await mctx.close()
} finally {
  await context.close()
  await browser.close()
}

/* ---------- 汇总 ---------- */
const okCount = results.length - failed
console.log('\n' + '='.repeat(60))
console.log(`  个人知识库验收：${okCount}/${results.length} 项通过`)
if (consoleErrors.length > 0) {
  console.log(`  ⚠️ 浏览器错误 ${consoleErrors.length} 条：`)
  for (const e of consoleErrors.slice(0, 6)) console.log(`     - ${e}`)
} else {
  console.log('  ✓ 浏览器零错误')
}
console.log(`  截图 ${shots.length} 张 → ${OUT_DIR}`)
console.log('='.repeat(60))

if (failed > 0) {
  console.log('\n未通过项：')
  for (const r of results.filter((r) => !r.ok)) console.log(`  ✗ ${r.name}  ${r.detail ?? ''}`)
  process.exitCode = 1
}
