/* v4 流式 mock 验证：通用/个人（双答案+提醒）/文档解惑问答 */
import { existsSync, writeFileSync, unlinkSync } from 'node:fs'
import { chromium } from 'playwright-core'
const CHROME = ['C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find((p) => existsSync(p))
const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await ctx.newPage()
const errors = []; page.on('pageerror', (e) => errors.push(e.message))
const problems = []
const check = (label, ok, detail = '') => { console.log(`${ok ? 'OK ' : 'FAIL'} ${label}${detail ? ' — ' + detail : ''}`); if (!ok) problems.push(label) }

async function login() {
  await page.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForTimeout(1500)
  await page.locator('input[type="text"]').first().fill('renhao')
  await page.locator('input[type="password"]').first().fill('abc123456')
  await page.locator('button[type="submit"]').first().click()
  await page.waitForTimeout(2000)
}
await login()

/* 1. 个人解惑：双答案流 */
await page.goto('http://localhost:5173/health', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1500)
await page.getByRole('button', { name: '新建个人解惑' }).click()
await page.getByPlaceholder(/标题/).fill('双答案验证')
await page.getByRole('button', { name: '创建', exact: true }).click()
await page.waitForTimeout(2000)
check('进入个人解惑对话页', page.url().includes('/health/chat/'), page.url())
await page.locator('textarea').first().fill('高血压怎么管理')
await page.locator('textarea').first().press('Enter')
await page.waitForTimeout(9000) // 双答案串行 ~5.4s + 流速
const labels = await page.getByText('系统版').count() + await page.getByText('个人版').count()
check('对照解答：系统版+个人版双卡', labels >= 2, `标签数=${labels}`)
check('个人版内容结合档案', (await page.getByText('低密度脂蛋白').count()) > 0)
check('健康注入提示（healthUsed）', (await page.getByText('已参考你的健康信息').count()) > 0)
// 依据来源默认折叠：点开最后一条回答的「依据来源」再断言徽标
const refBtn1 = page.getByRole('button', { name: /依据来源/ }).last()
if (await refBtn1.count()) { await refBtn1.click(); await page.waitForTimeout(500) }
check('依据来源含"我的资料"徽标', (await page.getByText('我的资料', { exact: true }).count()) > 0)
await page.screenshot({ path: 'tests/e2e/v4-dual.png' })

/* 2. 矛盾提醒：问体重 */
await page.locator('textarea').first().fill('我最近体重是70公斤，正常吗')
await page.locator('textarea').first().press('Enter')
await page.waitForTimeout(9500)
check('矛盾提醒卡出现', (await page.getByText('不一致').count()) > 0)
const updBtn = page.getByRole('button', { name: '更新为陈述值' })
if (await updBtn.count()) {
  await updBtn.click()
  await page.waitForTimeout(1200)
  check('提醒确认→写档案 toast', (await page.getByText('已更新健康指标').count()) > 0)
} else check('提醒确认→写档案 toast', false, '按钮未找到')

/* 3. 文档解惑：传文件→问答（引用=你的文件） */
await page.goto('http://localhost:5173/knowledge', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1200)
await page.getByRole('button', { name: '新建解惑会话' }).click()
await page.waitForTimeout(1800)
const md = 'tests/e2e/tmp-qa2.txt'
writeFileSync(md, '体检须知：空腹采血前请禁食 8 小时。')
await page.locator('aside input[type="file"]').setInputFiles(md)
await page.waitForTimeout(1500)
unlinkSync(md)
await page.locator('textarea').first().fill('采血前要注意什么')
await page.locator('textarea').first().press('Enter')
await page.waitForTimeout(8000)
check('文档解惑回答渲染', (await page.getByText('mock 文档解惑回答').count()) > 0)
const refBtn2 = page.getByRole('button', { name: /依据来源/ }).last()
if (await refBtn2.count()) { await refBtn2.click(); await page.waitForTimeout(500) }
check('引用含"你的文件"徽标', (await page.getByText('你的文件', { exact: true }).count()) > 0)
await page.screenshot({ path: 'tests/e2e/v4-file.png' })

/* 4. 空文件集提问 → 400 引导 */
await page.goto('http://localhost:5173/knowledge', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1200)
await page.getByRole('button', { name: '新建解惑会话' }).click()
await page.waitForTimeout(1800)
const disabled = await page.locator('textarea').first().isDisabled()
check('空文件集：输入框禁用引导上传', disabled)

check('零未捕获异常', errors.filter((e) => !e.includes('mock 未覆盖')).length === 0, errors.slice(0, 2).join(' | '))
await browser.close()
console.log(problems.length === 0 ? 'ALL PASS' : `${problems.length} failed`)
process.exit(problems.length === 0 ? 0 : 1)
