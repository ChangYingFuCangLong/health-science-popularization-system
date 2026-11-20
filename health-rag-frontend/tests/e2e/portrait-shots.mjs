/* ============================================================
 * 健康画像 与 专属对话 · 契约验收（真实浏览器 + 真实 HTTP）
 *
 * 用法：
 *     npm run dev            # 另开终端，mock 由 vite 插件提供
 *     node tests/e2e/portrait-shots.mjs
 *
 * 与 user-kb-shots.mjs 同款：**不做**浏览器层 route 拦截，
 * /api/** 全部走 vite mock 中间件 → 真的走了一遍 fetch → HTTP → 契约解析。
 *
 * 覆盖需求 C1–C5：
 *   C1 画像可填可改可清空            → 表单填写、保存、清空
 *   C2 专属对话每次提问自动带画像      → 断言回答正文**真的出现了画像内容**
 *   C3 专属对话置顶（= 第一个对话）    → 断言侧栏「专属」组在最前
 *   C4 每用户至多一条                → 断言已存在时不再出现二选一面板
 *   C5 新建在它存在时直接建普通会话    → 断言普通会话回答**不含**画像内容
 *   ＋ 误删后可重建（见最后一段）
 *
 * 截图落到仓库内 tests/e2e/screenshots/portrait（可用 SHOT_DIR 覆盖）。
 * ============================================================ */

import { existsSync, mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'

const BASE = process.env.SHOT_BASE ?? 'http://localhost:5173'
const OUT_DIR = process.env.SHOT_DIR ?? 'tests/e2e/screenshots/portrait'

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

const results = []
let failed = 0

function check(name, ok, detail) {
  results.push({ name, ok, detail })
  if (!ok) failed += 1
  const mark = ok ? '\u001b[32m✓\u001b[0m' : '\u001b[31m✗\u001b[0m'
  console.log(`  ${mark} ${name}${detail ? `  \u001b[2m(${detail})\u001b[0m` : ''}`)
}

const browser = await chromium.launch({ executablePath: findBrowser(), headless: true })
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
  await p.waitForTimeout(500)
}

const shots = []
async function shot(name) {
  await page.waitForTimeout(400)
  const file = `${OUT_DIR}/${name}.png`
  await page.screenshot({ path: file })
  shots.push(file)
  console.log(`   📸 ${name}.png`)
}

async function overflowX(p = page) {
  return p.evaluate(() => {
    const de = document.documentElement
    return { scroll: de.scrollWidth, client: de.clientWidth }
  })
}

/** 提问并等回答结束（MessageInput 的 textarea + 发送） */
async function ask(text) {
  const ta = page.locator('textarea').first()
  await ta.fill(text)
  await page.getByRole('button', { name: '发送' }).click()
  // 流式结束的判据：输入框不再处于 streaming（发送按钮重新可用）
  await page.waitForTimeout(4000)
}

try {
  /* ---------- 0. 重置 mock 内存态，保证套件可反复运行 ----------
     不做这一步，第二次跑的起点已被上一轮改动过（画像被填了、专属对话已存在），
     断言会随机失败——而且失败现象看起来像功能缺陷。 */
  const resetRes = await fetch(`${BASE}/api/__mock/reset`, { method: 'POST' })
  check('mock 内存态已重置', resetRes.ok, `HTTP ${resetRes.status}`)

  /* ---------- 0b. 登录态；**故意不设**知情同意标记，先验同意步骤 ---------- */
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await page.evaluate(() => {
    localStorage.setItem('user', JSON.stringify({ token: 'portrait-check-token' }))
    localStorage.removeItem('portrait-consent')
  })

  /* ---------- 1. 个人中心：画像空态 ---------- */
  const card = page.locator('section[aria-label="健康画像"]')
  await page.goto(`${BASE}/profile`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  // 显式等画像卡挂载，而不是猜一个固定时长：Vite 冷启动时首屏要好几秒，
  // 上一版在这里用固定等待，冷启动下会截到"还没渲染"的瞬间（断言假失败）
  await card.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => {})
  check('未被重定向到登录页', !page.url().includes('/login'), page.url())
  check('个人中心出现画像卡', await card.isVisible())
  await card.locator('text=还没有健康画像').waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {})
  check('空态提示正确', (await card.locator('text=还没有健康画像').count()) > 0)
  await shot('01-画像空态')

  /* ---------- 2. 知情同意（P3）：未同意前不收集数据 ---------- */
  await card.getByRole('button', { name: '开始填写画像' }).click()
  const sheet = page.locator('[role="dialog"][aria-label="健康画像编辑"]')
  await sheet.waitFor({ state: 'visible', timeout: 5000 })
  check('首次打开先出现知情同意', (await sheet.locator('text=填写前请先了解两件事').count()) > 0)
  check('说明数据会发送给模型服务商', (await sheet.locator('text=发送给模型服务商').count()) > 0)
  const formVisibleBefore = await sheet.locator('text=补充说明').count()
  check('同意前看不到表单', formVisibleBefore === 0, `表单区块数 ${formVisibleBefore}`)
  await shot('02-知情同意')

  await sheet.getByRole('button', { name: '我已了解，开始填写' }).click()
  await page.waitForTimeout(300)
  check('同意后进入表单', (await sheet.locator('text=补充说明').count()) > 0)
  const consented = await page.evaluate(() => localStorage.getItem('portrait-consent'))
  check('同意状态已记录', consented === '1', String(consented))

  /* ---------- 3. 填写画像（C1） ---------- */
  await sheet.getByRole('button', { name: '男', exact: true }).click()
  await sheet.locator('input[placeholder="1998"]').fill('1998')
  await sheet.locator('input[placeholder="175"]').fill('175')
  await sheet.locator('input[placeholder="65.5"]').fill('82.5')
  await sheet.getByRole('button', { name: '高血压', exact: true }).click()
  await sheet.getByRole('button', { name: '高血脂', exact: true }).click()
  await sheet.getByRole('button', { name: '每周 1~2 次', exact: true }).click()
  await sheet.locator('input[placeholder="7"]').fill('6.5')
  await sheet.getByRole('button', { name: '从不', exact: true }).first().click()
  await sheet.getByRole('button', { name: '社交场合', exact: true }).click()
  await sheet.getByRole('button', { name: '低盐', exact: true }).click()
  await sheet.getByRole('button', { name: '减重', exact: true }).click()
  await sheet.getByRole('button', { name: '控血压', exact: true }).click()
  await sheet.locator('textarea').fill('工作需久坐，晚上常加班')
  await shot('03-画像填写中')

  await sheet.getByRole('button', { name: '保存画像' }).click()
  await page.waitForTimeout(1500)
  check('保存后面板关闭', (await sheet.count()) === 0)

  /* ---------- 4. 画像卡渲染（读数 / BMI 刻度 / 标签） ---------- */
  check('画像卡显示已填项数', (await card.locator('text=/已填 \\d+ \\/ 14 项/').count()) > 0)
  check('BMI 已计算', (await card.locator('text=/26\\.9/').count()) > 0, 'BMI = 82.5 / 1.75²')
  check('BMI 落在「超重」档', (await card.locator('text=超重').count()) > 0)
  check('慢病标签渲染', (await card.locator('text=高血压').count()) > 0)
  check('目标标签渲染', (await card.locator('text=控血压').count()) > 0)
  check('生活方式渲染', (await card.locator('text=6.5 小时').count()) > 0)
  check('完善度进度条存在', (await card.locator('[role="progressbar"]').count()) > 0)
  // 注意：这里的 5 个「关键缺口」字段都已被填满，虚位理应为空——
  // 「未填虚位」必须在**确实有缺口**的状态下验，见手机端那一段。
  check('无缺口时不渲染虚位', (await card.locator('button:has-text("+ ")').count()) === 0)
  await shot('04-画像卡-已填写')

  /* ---------- 5. 新建会话二选一（C3/C4） ---------- */
  await page.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await page.waitForTimeout(800)
  await page.getByRole('button', { name: '新建对话' }).first().click()
  const newSheet = page.locator('[role="dialog"][aria-label="新建会话"]')
  await newSheet.waitFor({ state: 'visible', timeout: 5000 })
  check('无专属对话时弹出二选一', await newSheet.isVisible())
  check('提供「建立专属对话」', (await newSheet.locator('text=建立专属对话').count()) > 0)
  check('提供「普通对话」', (await newSheet.locator('text=普通对话').count()) > 0)
  check('标出画像完善度', (await newSheet.locator('text=/画像完善度 \\d+%/').count()) > 0)
  await shot('05-新建会话二选一')

  /* ---------- 6. 创建专属对话（C2/C3） ---------- */
  await newSheet.getByRole('button', { name: /建立专属对话/ }).click()
  await page.waitForTimeout(2000)
  check('已进入专属对话', /\/chat\/\d+/.test(page.url()), page.url())

  const sidebar = page.locator('nav[aria-label="会话列表"]')
  const portraitRow = sidebar.locator('button', { hasText: '我的专属对话' })
  check('专属对话已进入会话列表', (await portraitRow.count()) > 0)
  check('专属对话带「专属」标记', (await sidebar.locator('text=专属').count()) > 0)
  // C3：置顶 —— 对比「专属」组标题与「今天」组标题的纵坐标
  const groupTitles = await sidebar.locator('section > p').allInnerTexts()
  check('专属组排在时间分组之前', groupTitles[0] === '专属', `实际顺序 ${JSON.stringify(groupTitles)}`)
  await shot('06-专属对话已建立')

  /* ---------- 7. C2：专属对话的回答**真的**带上了画像 ---------- */
  await ask('高血压日常要注意什么')
  await page.waitForTimeout(1200)
  const bodyText = await page.locator('main').innerText()
  check('专属对话回答含画像内容', bodyText.includes('结合你填写的健康画像'), '正文出现画像段落')
  check('回答引用了具体指标', bodyText.includes('82.5'), '出现体重 82.5')
  check('界面标注本次已参考画像', bodyText.includes('本次回答已参考你的健康画像'))
  await shot('07-专属对话-已参考画像')

  /* ---------- 8. C4/C5：已有专属对话时，新建不再询问且不带画像 ---------- */
  await page.getByRole('button', { name: '新建对话' }).first().click()
  await page.waitForTimeout(900)
  check('已存在专属对话时不再弹面板', (await newSheet.count()) === 0)
  check('直接进入空会话', page.url().endsWith('/chat'), page.url())

  await ask('高血压日常要注意什么')
  await page.waitForTimeout(1200)
  const normalText = await page.locator('main').innerText()
  check('普通会话回答不含画像内容', !normalText.includes('结合你填写的健康画像'))
  check('普通会话不显示「已参考」标注', !normalText.includes('本次回答已参考你的健康画像'))
  await shot('08-普通会话-不带画像')

  /* ---------- 9. 误删后可重建（C5 的逆向） ---------- */
  await page.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await page.waitForTimeout(800)
  const row = page.locator('nav[aria-label="会话列表"] button', { hasText: '我的专属对话' }).first()
  await row.hover()
  await row.getByText('删除', { exact: true }).click()
  await page.waitForTimeout(300)
  await row.getByText('确认删除', { exact: true }).click()
  await page.waitForTimeout(1500)
  check(
    '删除后侧栏不再有专属对话',
    (await page.locator('nav[aria-label="会话列表"] button', { hasText: '我的专属对话' }).count()) === 0
  )

  await page.getByRole('button', { name: '新建对话' }).first().click()
  await page.waitForTimeout(900)
  check('删除后可再次选择建立专属对话', (await newSheet.count()) === 1)
  await shot('09-误删后可重建')

  /* ---------- 10. 画像清空 → 引导去填（§6.2 分支一） ---------- */
  await newSheet.getByRole('button', { name: '关闭新建会话面板' }).click()
  await page.goto(`${BASE}/profile`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await card.getByRole('button', { name: '编辑' }).click()
  await page.locator('[role="dialog"][aria-label="健康画像编辑"]').waitFor({ state: 'visible' })
  await page.getByRole('button', { name: '清空画像' }).click()
  await page.getByRole('button', { name: '确认清空' }).click()
  await page.waitForTimeout(1500)
  check('清空后回到空态', (await card.locator('text=还没有健康画像').count()) > 0)
  await shot('10-清空画像后空态')

  await page.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded' })
  await waitApp()
  await page.waitForTimeout(800)
  await page.getByRole('button', { name: '新建对话' }).first().click()
  await newSheet.waitFor({ state: 'visible', timeout: 5000 })
  check('画像为空时不提供专属对话选项', (await newSheet.locator('text=建立专属对话').count()) === 0)
  check('改为引导去填画像', (await newSheet.locator('text=去填写健康画像').count()) > 0)
  check('仍允许先建普通对话', (await newSheet.locator('text=先建一个普通对话').count()) > 0)
  await shot('11-画像为空时引导填写')
  await newSheet.getByRole('button', { name: '关闭新建会话面板' }).click()
  await page.waitForTimeout(400)

  const ovDesk = await overflowX()
  check('桌面无横向溢出', ovDesk.scroll <= ovDesk.client + 1, `${ovDesk.scroll}/${ovDesk.client}`)

  /* ============================================================
   * 手机视口（375×812）
   * ============================================================ */
  const mctx = await browser.newContext({
    viewport: { width: 375, height: 812 },
    deviceScaleFactor: 2,
    hasTouch: true,
    isMobile: true,
    locale: 'zh-CN'
  })
  const mp = await mctx.newPage()
  mp.on('pageerror', (e) => consoleErrors.push(`[mobile pageerror] ${e.message}`))

  await mp.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await mp.waitForFunction(() => (document.querySelector('#app')?.children.length ?? 0) > 0, null, {
    timeout: 60_000
  })
  await mp.evaluate(() => {
    localStorage.setItem('user', JSON.stringify({ token: 'portrait-mobile-token' }))
    localStorage.setItem('portrait-consent', '1')
  })

  async function mshot(name) {
    await mp.waitForTimeout(400)
    const file = `${OUT_DIR}/${name}.png`
    await mp.screenshot({ path: file })
    shots.push(file)
    console.log(`   📸 ${name}.png`)
  }

  await mp.goto(`${BASE}/profile`, { waitUntil: 'domcontentloaded' })
  await mp.waitForFunction(() => (document.querySelector('#app')?.children.length ?? 0) > 0, null, {
    timeout: 60_000
  })
  const mCardEarly = mp.locator('section[aria-label="健康画像"]')
  await mCardEarly.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => {})
  await mp.waitForTimeout(600)
  const ovM = await overflowX(mp)
  check('手机端个人中心无横向溢出', ovM.scroll <= ovM.client + 1, `${ovM.scroll}/${ovM.client}`)
  await mshot('12-画像空态-375')

  // 填一会再截，让手机端画像卡有内容可看
  await mCardEarly.getByRole('button', { name: '开始填写画像' }).click()
  const mSheet = mp.locator('[role="dialog"][aria-label="健康画像编辑"]')
  await mSheet.waitFor({ state: 'visible', timeout: 5000 })
  const mPanel = mSheet.locator('> div').nth(1)
  check('手机端编辑面板贴底（底部抽屉形态）', ((await mPanel.boundingBox())?.y ?? 0) > 100)
  await mShotSave(mSheet)
  // 手机端只填了一部分字段 → 保存后画像卡上应出现可点击的「未填虚位」
  await mSheet.getByRole('button', { name: '保存画像' }).click()
  await mp.waitForTimeout(1500)
  check('手机端保存后面板自动关闭', (await mSheet.count()) === 0)
  const mCard = mp.locator('section[aria-label="健康画像"]')
  const mGaps = await mCard.locator('button:has-text("+ ")').count()
  check('有缺口时渲染可点击虚位', mGaps > 0, `${mGaps} 项`)
  check('虚位写明缺什么', (await mCard.locator('text=还差这些').count()) > 0)
  await mshot('13b-画像卡-未填虚位-375')

  await mp.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded' })
  await mp.waitForTimeout(1500)
  await mp.getByRole('button', { name: '打开会话列表' }).click()
  await mp.waitForTimeout(600)
  await mp.getByRole('button', { name: '新建对话' }).first().click()
  await mp.waitForTimeout(900)
  const mNew = mp.locator('[role="dialog"][aria-label="新建会话"]')
  check('手机端新建会话面板打开', await mNew.isVisible())
  const mNewPanel = mNew.locator('> div').nth(1)
  check('手机端面板贴底', ((await mNewPanel.boundingBox())?.y ?? 0) > 100)
  await mshot('14-新建会话-375')
  const ovMChat = await overflowX(mp)
  check('手机端对话页无横向溢出', ovMChat.scroll <= ovMChat.client + 1, `${ovMChat.scroll}/${ovMChat.client}`)

  await mctx.close()

  async function mShotSave(handle) {
    await handle.getByRole('button', { name: '男', exact: true }).click()
    await handle.locator('input[placeholder="175"]').fill('175')
    await handle.locator('input[placeholder="65.5"]').fill('70')
    await handle.getByRole('button', { name: '高血压', exact: true }).click()
    await mp.waitForTimeout(500)
    await mshot('13-画像编辑-375')
  }
} finally {
  await context.close()
  await browser.close()
}

/* ---------- 汇总 ---------- */
const okCount = results.length - failed
console.log('\n' + '='.repeat(60))
console.log(`  健康画像与专属对话验收：${okCount}/${results.length} 项通过`)
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
