/**
 * 带登录态的截图脚本。
 *
 * <p>为什么需要它：shot.mjs 是未登录状态，而 InfoHtml / InfoOther / BlogDetail / BlogEdit
 * 这些页面都会被路由守卫重定向到登录页，截出来的全是登录页，等于没验证。
 *
 * <p>登录态怎么注入：前端用 pinia-plugin-persistedstate 持久化，store id 是 `Hmdp-User`，
 * 落盘位置就是 localStorage 的 `Hmdp-User` key，值为 { token, userInfo } 的 JSON。
 * 所以只要先调登录接口拿 token，再用 evaluateOnNewDocument 在文档创建前写入 localStorage 即可。
 *
 * <p>用法：
 *   node shot-auth.mjs /InfoHtml /InfoOther /blogDetail/1
 */
import puppeteer from 'puppeteer-core'
import { existsSync, mkdirSync } from 'node:fs'

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
]
const CHROME = CHROME_CANDIDATES.find((p) => existsSync(p))
if (!CHROME) {
  console.error('未找到 Chrome/Edge')
  process.exit(1)
}

const BASE = 'http://localhost:5173'
const API = 'http://localhost:8085'
const OUT = 'D:\\Java\\hmdp-plus\\perf\\screenshots'
const PHONE = '15756212560'

if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true })

// ---------- 1. 走真实接口登录，拿 token ----------
async function login() {
  const codeResp = await fetch(`${API}/user/code?phone=${PHONE}`, { method: 'POST' })
  const codeJson = await codeResp.json()
  if (!codeJson.success) throw new Error('获取验证码失败: ' + JSON.stringify(codeJson))
  const code = codeJson.data

  const loginResp = await fetch(`${API}/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: PHONE, code })
  })
  const loginJson = await loginResp.json()
  if (!loginJson.success) throw new Error('登录失败: ' + JSON.stringify(loginJson))
  return loginJson.data
}

const token = await login()
console.log('✅ 登录成功，token 长度', token.length)

// ---------- 2. 注入 localStorage 后截图 ----------
const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage']
})

const page = await browser.newPage()
await page.setViewport({ width: 393, height: 852, deviceScaleFactor: 2 })

// 在每个新文档创建前写入登录态，保证 store 初始化时就能读到
await page.evaluateOnNewDocument((t) => {
  localStorage.setItem('Hmdp-User', JSON.stringify({ token: t, userInfo: {} }))
}, token)

const routes = process.argv.slice(2)
if (routes.length === 0) {
  console.error('用法: node shot-auth.mjs <route> [route...]')
  await browser.close()
  process.exit(1)
}

let failed = 0
for (const route of routes) {
  const url = BASE + route
  try {
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 30000 })
    // 等首屏渲染与接口回填
    await new Promise((r) => setTimeout(r, 1800))

    // 判断是否被重定向到登录页（说明登录态没生效）
    const finalPath = await page.evaluate(() => location.pathname)
    const metrics = await page.evaluate(() => ({
      vw: window.innerWidth,
      scrollW: document.documentElement.scrollWidth
    }))
    const overflow = metrics.scrollW - metrics.vw
    const name = route.replace(/^\//, '').replace(/[/?]/g, '_') || 'root'
    const file = `${OUT}\\auth_${name}.png`
    await page.screenshot({ path: file, fullPage: true })

    const redirected = finalPath.startsWith('/login')
    const flag = redirected ? '⚠️ 被重定向到登录页' : '✅'
    if (redirected) failed++
    console.log(
      `${flag} ${route}  vw=${metrics.vw} scrollW=${metrics.scrollW} overflow=${overflow}px  → auth_${name}.png`
    )
    void url
  } catch (e) {
    failed++
    console.log(`❌ ${route}  失败: ${e.message}`)
  }
}

await browser.close()
if (failed > 0) console.log(`\n有 ${failed} 个页面未取到登录态后的内容`)
