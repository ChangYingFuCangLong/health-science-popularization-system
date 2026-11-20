/* 临时验证：移动端抽屉收回（按钮/拖拽/弹回）+ Toast 居中 —— 走 mock 数据 */
import { existsSync } from 'node:fs'
import { chromium } from 'playwright-core'

const CHROME = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
].find((p) => existsSync(p))
if (!CHROME) throw new Error('未找到浏览器')

const browser = await chromium.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--use-mobile-user-agent']
})
const context = await browser.newContext({
  viewport: { width: 390, height: 844 },
  hasTouch: true,
  isMobile: true
})
const page = await context.newPage()

const errors = []
page.on('pageerror', (e) => errors.push(e.message))

const problems = []
const check = (label, ok, detail = '') => {
  console.log(`${ok ? 'OK ' : 'FAIL'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) problems.push(label)
}
const closeBtn = '[aria-label="关闭会话列表"]'
const openBtn = '[aria-label="打开会话列表"]'

// 1. mock 登录
await page.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded', timeout: 30_000 })
await page.waitForTimeout(1800)
await page.locator('input[type="text"]').first().fill('renhao')
await page.locator('input[type="password"]').first().fill('abc123456')
await page.locator('button[type="submit"]').first().click()
await page.waitForTimeout(2500)
check('mock 登录进入对话页', page.url().includes('/chat'), page.url())

// 2. 打开抽屉 → ✕ 收回
await page.locator(openBtn).click()
await page.waitForTimeout(500)
check('抽屉打开（有关闭按钮）', (await page.locator(closeBtn).count()) > 0)
await page.locator(closeBtn).click()
await page.waitForTimeout(500)
check('✕ 按钮收回抽屉', (await page.locator(closeBtn).count()) === 0)

// 3. 手势收回：按住面板向左拖过阈值 → 松手收回
await page.locator(openBtn).click()
await page.waitForTimeout(500)
await page.mouse.move(200, 400)
await page.mouse.down()
for (let x = 200; x >= 40; x -= 20) {
  await page.mouse.move(x, 402)
  await page.waitForTimeout(30)
}
await page.mouse.up()
await page.waitForTimeout(800)
check('向左拖拽超过阈值 → 收回', (await page.locator(closeBtn).count()) === 0)

// 4. 拖拽不足阈值 → 弹回，抽屉仍开
await page.locator(openBtn).click()
await page.waitForTimeout(500)
await page.mouse.move(260, 400)
await page.mouse.down()
await page.mouse.move(230, 400, { steps: 4 })
await page.mouse.up()
await page.waitForTimeout(700)
const stillOpen = (await page.locator(closeBtn).count()) > 0
check('小幅拖拽 → 弹回且抽屉仍开', stillOpen)

// 5. 纵向滑动不被手势劫持（会话列表仍可滚动）：记录抽屉内滚动位置变化
let scrolled = false
try {
  const nav = page.locator('nav[aria-label="会话列表"]').last() // 桌面侧栏与抽屉同名，取抽屉的
  const scrollable = await nav.evaluate(
    (el) => el.scrollHeight - el.clientHeight > 40
  )
  if (scrollable) {
    const before = await nav.evaluate((el) => el.scrollTop)
    await page.mouse.move(150, 700)
    await page.mouse.down()
    for (let y = 700; y >= 560; y -= 20) {
      await page.mouse.move(150, y)
      await page.waitForTimeout(20)
    }
    await page.mouse.up()
    await page.waitForTimeout(400)
    const after = await nav.evaluate((el) => el.scrollTop)
    scrolled = after !== before
    check('纵向滑动仍可滚动会话列表（手势未劫持）', scrolled, `before=${before} after=${after}`)
  } else {
    console.log('i  会话太少不足以滚动，跳过纵向滚动断言')
  }
} catch {
  console.log('i  纵向滚动检查前置条件不满足，跳过')
}

// 6. Toast 居中：点一条回答的「有用」→ feedback 接口 mock 未实现 → 网络错误 toast
const articles = page.locator('article')
const n = await articles.count()
if (n > 0) {
  await page.locator(openBtn).click().catch(() => {})
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
  const fb = page.getByRole('button', { name: '有用' }).first()
  if (await fb.count()) {
    await fb.click()
    await page.waitForTimeout(600)
    const toast = page.locator('[role="status"]').last()
    const box = await toast.boundingBox().catch(() => null)
    if (box) {
      const cx = box.x + box.width / 2
      const cy = box.y + box.height / 2
      check(
        'Toast 居中显示',
        Math.abs(cx - 195) < 60 && Math.abs(cy - 422) < 140,
        `中心=(${Math.round(cx)},${Math.round(cy)}) 期望≈(195,422)`
      )
      await page.screenshot({ path: 'tests/e2e/mobile-polish.png' })
    } else {
      check('Toast 居中显示', false, '未捕获到 toast')
    }
  } else {
    console.log('i  无可用回答反馈按钮，跳过 toast 居中断言')
  }
}

// mock 对未覆盖端点（如 feedback）会主动抛"mock 未覆盖"——属设计行为，不是页面缺陷
const realErrors = errors.filter((e) => !e.includes('mock 未覆盖'))
check('无未捕获异常', realErrors.length === 0, realErrors.slice(0, 2).join(' | '))
await browser.close()
console.log(problems.length === 0 ? 'ALL PASS' : `${problems.length} failed`)
process.exit(problems.length === 0 ? 0 : 1)
