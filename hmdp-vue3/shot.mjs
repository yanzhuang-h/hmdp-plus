/**
 * 截图脚本：用系统 Chrome 按指定视口渲染页面，用于视觉自查。
 *
 * 放在 hmdp-vue3 根目录是为了能解析本项目的 node_modules（puppeteer-core）。
 *
 * 为什么不用 chrome --headless --screenshot：
 *   Windows 版 Chrome 有最小窗口宽度限制（实测被强制成 512px），
 *   --window-size=393 不生效，导致截图右侧被裁掉、误判为"页面溢出"。
 *   puppeteer 的 setViewport 可以精确控制视口，不受该限制。
 *
 * 用法：
 *   node shot.mjs                 # 默认截首页
 *   node shot.mjs /index /shopList
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
const OUT = 'D:\\Java\\hmdp-plus\\perf\\screenshots'
if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true })

// iPhone 16: 393 x 852 CSS px
const VIEWPORT = { width: 393, height: 852, deviceScaleFactor: 2, isMobile: true, hasTouch: true }

const paths = process.argv.slice(2)
if (paths.length === 0) paths.push('/index')

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--font-render-hinting=none']
})

try {
  const page = await browser.newPage()
  await page.setViewport(VIEWPORT)

  for (const p of paths) {
    const name = p.replace(/\//g, '_').replace(/^_/, '') || 'root'
    const url = p.startsWith('http') ? p : `${BASE}${p}`
    try {
      await page.goto(url, { waitUntil: 'networkidle0', timeout: 30000 })
      await new Promise((r) => setTimeout(r, 1800))

      const metrics = await page.evaluate(() => {
        const de = document.documentElement
        return { vw: de.clientWidth, scrollW: de.scrollWidth, overflow: de.scrollWidth - de.clientWidth }
      })

      const file = `${OUT}\\${name}.png`
      await page.screenshot({ path: file, fullPage: false })
      console.log(`✅ ${p}  vw=${metrics.vw} scrollW=${metrics.scrollW} overflow=${metrics.overflow}px  → ${name}.png`)
    } catch (e) {
      console.error(`❌ ${p}: ${e.message}`)
    }
  }
} finally {
  await browser.close()
}
