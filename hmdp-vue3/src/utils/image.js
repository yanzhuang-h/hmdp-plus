/**
 * 图片路径解析。
 *
 * 背景：后端返回的图片字段有两种形态
 *   1. 站内相对路径：`/imgs/blogs/1/2/xxx.jpg`（博客、头像、券等）
 *   2. 外部绝对地址：`https://qcloud.dpfile.com/...`（商铺图片）
 *
 * 当前图片资源由 Vite 从 `src/assets` 提供（后端上传目录也指向该处），
 * 因此站内相对路径需要补 `/src/assets` 前缀。
 *
 * 统一收敛到这里的好处：以后若改为由 nginx 提供（`/imgs/...` 直连），
 * 只需改 `IMAGE_BASE` 一处，无需在 8 个组件里逐个排查。
 */

/** 站内图片前缀。改为 '' 即可切到「由 nginx 直接提供」的模式 */
const IMAGE_BASE = '/src/assets'

/** 默认头像 */
export const DEFAULT_ICON = `${IMAGE_BASE}/imgs/icons/default-icon.png`

/**
 * 解析单个图片路径。
 *
 * @param {string} path 后端返回的路径，可能为空
 * @param {string} fallback 兜底图（默认头像）
 * @returns {string} 可直接用于 img.src 的地址
 */
export function resolveImage(path, fallback = DEFAULT_ICON) {
  if (!path || typeof path !== 'string') return fallback
  const p = path.trim()
  if (!p) return fallback

  // 外部绝对地址、data URI、已带前缀的，原样返回
  if (
    p.startsWith('http://') ||
    p.startsWith('https://') ||
    p.startsWith('data:') ||
    p.startsWith(IMAGE_BASE)
  ) {
    return p
  }

  // 站内相对路径：补前缀，保证只有一个斜杠
  return `${IMAGE_BASE}/${p.replace(/^\/+/, '')}`
}

/**
 * 解析「商铺分类」图标。
 *
 * 分类图标与其它图片的路径约定不同：后端 `tb_shop_type.icon` 存的是
 * `/types/ms.png`（**不含 `/imgs` 前缀**），而实际文件位于 `imgs/types/` 下。
 * 因此不能直接用 {@link resolveImage}（会拼成 `/src/assets/types/ms.png`，
 * 落到 Vite 的 SPA fallback 上，表现为「返回 200 但不是图片」）。
 *
 * @param {string} icon 后端返回的分类图标，如 `/types/ms.png`
 */
export function resolveTypeIcon(icon, fallback = '') {
  if (!icon || typeof icon !== 'string') return fallback
  const p = icon.trim()
  if (!p) return fallback
  if (p.startsWith('http://') || p.startsWith('https://') || p.startsWith('data:')) return p
  // 后端若已返回带 /imgs 的完整路径，直接走通用解析
  if (p.startsWith('/imgs/') || p.startsWith(IMAGE_BASE)) return resolveImage(p)
  return `${IMAGE_BASE}/imgs/${p.replace(/^\/+/, '')}`
}

/**
 * 取「逗号分隔的多图字符串」中的第一张。
 *
 * @param {string} images 例如 `/imgs/blogs/a.jpg,/imgs/blogs/b.jpg`
 */
export function firstImage(images, fallback = DEFAULT_ICON) {
  if (!images || typeof images !== 'string') return fallback
  const first = images.split(',')[0]?.trim()
  return first ? resolveImage(first) : fallback
}

/**
 * 把逗号分隔的多图字符串解析成数组（用于轮播/图集）。
 */
export function splitImages(images) {
  if (!images || typeof images !== 'string') return []
  return images
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => resolveImage(s))
}
