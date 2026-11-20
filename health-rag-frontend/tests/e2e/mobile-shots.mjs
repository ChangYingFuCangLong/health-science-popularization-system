/* ============================================================
 * 移动端适配验收（真实浏览器 + 手机视口截图）
 *
 * 用法：
 *     npm run dev            # 另开终端
 *     node tests/e2e/mobile-shots.mjs
 *
 * 与其余三套 e2e 的区别：
 *   1) 后端无需在线 —— 全部 /api/** 由浏览器层 route 拦截并 mock，
 *      验收目标是「布局壳与交互」，不是数据面。
 *   2) 主视口是 375×812（iPhone X 级别），末段再用 1280×900 做桌面回归对照。
 *   3) 产出截图 + 断言结论，输出目录可用 SHOT_DIR 覆盖。
 *
 * 截图落到仓库内 tests/e2e/screenshots/mobile（可用 SHOT_DIR 覆盖）。
 * ============================================================ */

import { existsSync, mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'

const BASE = process.env.SHOT_BASE ?? 'http://localhost:5173'
const OUT_DIR = process.env.SHOT_DIR ?? 'tests/e2e/screenshots/mobile'

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

/* ---------------- mock 数据（字段严格对齐 src/api/types.ts） ---------------- */

const iso = (offsetMin) => new Date(Date.now() - offsetMin * 60_000).toISOString()

const MOCK_USER = {
  id: 1,
  username: 'renhao',
  nickname: '任浩',
  email: null,
  avatar: null,
  role: 'ADMIN',
  createTime: iso(60 * 24 * 30)
}

const MOCK_SESSIONS = [
  { id: 1, title: '高血压患者日常饮食需要注意什么？', messageCount: 6, createTime: iso(30), updateTime: iso(5) },
  { id: 2, title: '每天运动多久比较合适', messageCount: 4, createTime: iso(600), updateTime: iso(120) },
  { id: 3, title: '长期熬夜会有哪些危害', messageCount: 8, createTime: iso(5000), updateTime: iso(4000) }
]

const MOCK_CATEGORIES = [
  { id: 1, name: '慢性病管理', count: 1 },
  { id: 2, name: '合理膳食', count: 1 },
  { id: 3, name: '儿童青少年健康', count: 1 }
]

const MOCK_ARTICLES = [
  {
    id: 101,
    title: '高血压患者的日常饮食要点',
    summary: '低盐、控油、增加钾摄入',
    category: '慢性病管理',
    categoryName: '慢性病管理',
    charCount: 1820,
    updateTime: iso(60 * 24 * 3)
  },
  {
    id: 102,
    title: '中国居民膳食指南（2022）核心推荐',
    summary: '食物多样、谷类为主',
    category: '合理膳食',
    categoryName: '合理膳食',
    charCount: 2400,
    updateTime: iso(60 * 24 * 10)
  },
  {
    id: 103,
    title: '中小学生睡眠时间要求',
    summary: '小学生 10 小时、初中生 9 小时',
    category: '儿童青少年健康',
    categoryName: '儿童青少年健康',
    charCount: 960,
    updateTime: iso(60 * 24 * 20)
  }
]

/* ⚠️ 分页接口必须回 { total, pages, current, size, records }：
   页面取 page.records 赋给列表，若只回裸数组会得到 undefined，
   渲染时读 .length 直接抛错（实测踩过）。 */
function mockFor(pathname) {
  if (pathname === '/api/auth/me') return MOCK_USER
  if (pathname === '/api/chat/sessions') return MOCK_SESSIONS
  if (/^\/api\/chat\/sessions\/\d+\/messages$/.test(pathname)) {
    return { total: 0, pages: 0, current: 1, size: 50, records: [] }
  }
  if (pathname === '/api/knowledge/categories') return MOCK_CATEGORIES
  if (pathname === '/api/knowledge/articles') {
    return { total: MOCK_ARTICLES.length, pages: 1, current: 1, size: 12, records: MOCK_ARTICLES }
  }
  if (/^\/api\/knowledge\/articles\/\d+\/related$/.test(pathname)) return { articles: [] }
  return []
}

/* ---------------- 断言助手 ---------------- */

const problems = []
function check(label, ok, detail = '') {
  console.log(`${ok ? '✅' : '❌'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) problems.push(label)
}

/* ---------------- 启动 ---------------- */

if (!existsSync(OUT_DIR)) mkdirSync(OUT_DIR, { recursive: true })

const browser = await chromium.launch({ executablePath: findBrowser(), headless: true })

/* 手机视口：375×812 + 2x 像素密度 + 触摸，贴近真实设备 */
const context = await browser.newContext({
  viewport: { width: 375, height: 812 },
  deviceScaleFactor: 2,
  isMobile: true,
  hasTouch: true,
  locale: 'zh-CN'
})
const page = await context.newPage()

/* 收集浏览器侧错误：白屏时靠它定位，而不是猜 */
const consoleErrors = []
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => {
  const frames = (e.stack ?? '').split('\n').slice(1, 5).join('\n      ')
  consoleErrors.push(`[pageerror @ ${page.url()}]\n    ${e.message}\n      ${frames}`)
})

/** 等应用真正挂载（Vite dev 按需编译，首次访问需要数秒，固定 timeout 会截到白屏） */
async function waitApp(p = page) {
  await p.waitForFunction(() => (document.querySelector('#app')?.children.length ?? 0) > 0, null, {
    timeout: 60_000
  })
  await p.evaluate(() => document.fonts.ready)
  await p.waitForTimeout(500)
}

/* 注意：pattern 必须限定到 /api/ 前缀。
   写成 '**​/api/**' 会连同源码模块 http://localhost:5173/src/api/*.ts 一起拦截，
   导致前端模块加载失败、应用挂载不起来（呈现为白屏）。 */
const API_GLOB = `${BASE}/api/**`

await page.route(API_GLOB, async (route) => {
  const pathname = new URL(route.request().url()).pathname
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'ok', data: mockFor(pathname) })
  })
})

const shots = []
async function shot(name) {
  await page.waitForTimeout(500)
  const file = `${OUT_DIR}/${name}.png`
  await page.screenshot({ path: file })
  shots.push(file)
  console.log(`   📸 ${name}.png`)
}

/** 页面级横向溢出检测（移动端最常见的隐性缺陷） */
async function overflowX() {
  return page.evaluate(() => {
    const de = document.documentElement
    return { scroll: de.scrollWidth, client: de.clientWidth }
  })
}

try {
  /* ---------- 1. 登录页 ---------- */
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await shot('01-登录页-375')
  const ov1 = await overflowX()
  check('登录页无横向溢出', ov1.scroll <= ov1.client + 1, `${ov1.scroll}/${ov1.client}`)

  /* ---------- 2. 注入登录态，进入对话页 ---------- */
  await page.evaluate(() => {
    localStorage.setItem('user', JSON.stringify({ token: 'layout-check-token' }))
  })
  await page.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await shot('02-对话页-375')

  const ov2 = await overflowX()
  check('对话页无横向溢出', ov2.scroll <= ov2.client + 1, `${ov2.scroll}/${ov2.client}`)

  /* ---------- 3. 底部标签栏 ---------- */
  const tabbar = page.locator('nav[aria-label="主导航"]')
  check('底部标签栏存在且可见', await tabbar.isVisible())
  const tabCount = await tabbar.locator('a').count()
  check('底部标签栏含 4 个入口', tabCount === 4, `实际 ${tabCount}`)

  const tabBox = await tabbar.locator('a').first().boundingBox()
  check('标签触摸高度 ≥44px', (tabBox?.height ?? 0) >= 44, `${Math.round(tabBox?.height ?? 0)}px`)

  /* ---------- 4. 会话抽屉：打开 → 关闭按钮 → 会话操作可见 ---------- */
  await page.getByRole('button', { name: '打开会话列表' }).click()
  await page.waitForTimeout(600)
  await shot('03-会话抽屉-打开')

  const drawer = page.locator('[role="dialog"][aria-label="会话列表"]')
  check('抽屉已打开', await drawer.isVisible())

  /* ⚠️ 选择器必须限定在抽屉内：
     页面上同时存在两个 SessionSidebar —— 桌面常驻的那个（md:hidden 下 display:none）
     与抽屉里的这个。若直接写 nav[aria-label="会话列表"]，.first() 会选中隐藏的那个，
     得到 boundingBox = null 的假失败。 */
  const drawerNav = drawer.locator('nav[aria-label="会话列表"]')

  const drawerBox = await drawer.boundingBox()
  check(
    '抽屉宽度合理（≤300px）',
    (drawerBox?.width ?? 0) > 0 && (drawerBox?.width ?? 0) <= 300,
    `${Math.round(drawerBox?.width ?? 0)}px`
  )

  const closeBtn = page.getByRole('button', { name: '关闭会话列表' })
  check('抽屉有关闭按钮', await closeBtn.isVisible())

  /* 关键回归点：触屏上重命名/删除必须可见（改造前由 group-hover 控制，触屏永不出现） */
  const renameVisible = await drawerNav
    .locator('button', { hasText: '重命名' })
    .first()
    .isVisible()
    .catch(() => false)
  check('触屏下「重命名」操作可见', renameVisible)

  const delVisible = await drawerNav
    .locator('button', { hasText: '删除' })
    .first()
    .isVisible()
    .catch(() => false)
  check('触屏下「删除」操作可见', delVisible)

  /* 会话行触摸高度 */
  const rowBox = await drawerNav.locator('button').first().boundingBox()
  check('会话行触摸高度 ≥44px', (rowBox?.height ?? 0) >= 44, `${Math.round(rowBox?.height ?? 0)}px`)

  /* 点关闭按钮 → 抽屉收起 */
  await closeBtn.click()
  await page.waitForTimeout(500)
  check('点关闭按钮后抽屉收起', !(await drawer.isVisible()))
  await shot('04-会话抽屉-已关闭')

  /* ESC 关闭 */
  await page.getByRole('button', { name: '打开会话列表' }).click()
  await page.waitForTimeout(500)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
  check('ESC 可关闭抽屉', !(await drawer.isVisible()))

  /* ---------- 5. 底部标签栏导航 ---------- */
  await tabbar.locator('a', { hasText: '知识探索' }).click()
  await page.waitForTimeout(1200)
  check('底部标签可跳转知识探索', page.url().includes('/knowledge'), page.url())
  await shot('05-知识探索-375')

  await tabbar.locator('a', { hasText: '健康中心' }).click()
  await page.waitForTimeout(1200)
  await shot('06-健康中心-375')

  await tabbar.locator('a', { hasText: '我的' }).click()
  await page.waitForTimeout(1000)
  await shot('07-个人中心-375')

  const ov3 = await overflowX()
  check('个人中心无横向溢出', ov3.scroll <= ov3.client + 1, `${ov3.scroll}/${ov3.client}`)

  /* ---------- 6. 管理端：侧栏必须收起 ---------- */
  await page.goto(`${BASE}/admin/dashboard`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await shot('08-管理端-375')

  const asideVisible = await page
    .locator('.admin-aside')
    .isVisible()
    .catch(() => false)
  check('管理端固定侧栏已在手机端隐藏', !asideVisible)

  const burger = page.getByRole('button', { name: '打开管理菜单' })
  check('管理端顶栏有汉堡按钮', await burger.isVisible())

  await burger.click()
  await page.waitForTimeout(700)
  await shot('09-管理端抽屉菜单')
  const menuVisible = await page.locator('.el-drawer').isVisible().catch(() => false)
  check('管理端抽屉菜单可打开', menuVisible)

  const ov4 = await overflowX()
  check('管理端无页面级横向溢出', ov4.scroll <= ov4.client + 1, `${ov4.scroll}/${ov4.client}`)

  /* ---------- 7. 桌面回归对照（1280×900，e2e 断言的视口） ---------- */
  const deskPage = await context.newPage()
  await deskPage.setViewportSize({ width: 1280, height: 900 })
  await deskPage.route(API_GLOB, async (route) => {
    const pathname = new URL(route.request().url()).pathname
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: mockFor(pathname) })
    })
  })
  await deskPage.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded' })
  await waitApp(deskPage)
  await deskPage.screenshot({ path: `${OUT_DIR}/10-桌面回归对照-1280.png` })
  shots.push(`${OUT_DIR}/10-桌面回归对照-1280.png`)
  console.log('   📸 10-桌面回归对照-1280.png')

  check('桌面端底部标签栏隐藏', !(await deskPage.locator('nav[aria-label="主导航"]').isVisible()))
  check('桌面端会话侧栏常驻', await deskPage.locator('nav[aria-label="会话列表"]').isVisible())
  const headerText = await deskPage.locator('header').first().innerText()
  check('桌面端顶栏仍含「健康科普助手」（e2e 契约）', headerText.includes('健康科普助手'))
  check('桌面端仍有「退出」按钮（e2e 契约）', await deskPage.getByRole('button', { name: '退出' }).first().isVisible())

  await deskPage.goto(`${BASE}/admin/dashboard`, { waitUntil: 'domcontentloaded' })
  await waitApp(deskPage)
  check('桌面端管理侧栏仍常驻', await deskPage.locator('.admin-aside').isVisible())
  await deskPage.screenshot({ path: `${OUT_DIR}/11-桌面回归-管理端.png` })
  shots.push(`${OUT_DIR}/11-桌面回归-管理端.png`)

  /* ---------- 汇总 ---------- */
  if (consoleErrors.length > 0) {
    console.log(`\n⚠️  浏览器控制台错误 ${consoleErrors.length} 条：`)
    for (const e of consoleErrors.slice(0, 10)) console.log(`   - ${e.slice(0, 200)}`)
  }

  console.log('\n' + '='.repeat(56))
  if (problems.length === 0) {
    console.log('✅ 全部检查通过')
  } else {
    console.log(`❌ ${problems.length} 项未通过：`)
    for (const p of problems) console.log(`   - ${p}`)
  }
  console.log(`截图输出：${OUT_DIR}`)
  console.log('='.repeat(56))
} catch (err) {
  console.error('❌ 执行中断：', err.message)
  problems.push(`执行中断: ${err.message}`)
} finally {
  await browser.close()
}

process.exit(problems.length === 0 ? 0 : 1)
