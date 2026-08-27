/**
 * 「路由懒加载的 chunk 拉不到了」的判据。
 *
 * 单独一个文件是为了能被单测直接 import——`router/index.ts` 在模块作用域就
 * `createRouter()` 并把 30 多个页面组件的 import 铺开，为了测一个字符串匹配把那些一并拉起来
 * 不划算。
 *
 * 这件事在本项目里不是边缘情况：Nginx 给 index.html 和静态资源都打了 `no-store`
 * （见 nginx.conf），重新部署后镜像里的旧 chunk 文件直接就没了，而**开着的标签页
 * 手里还攥着旧 index.html 里那串带 hash 的文件名**。用户点一个还没访问过的菜单，
 * 那次动态 import 404，vue-router 中止导航——页面上什么都不发生，控制台一行红字。
 *
 * **各家浏览器的措辞完全不同，而这里是纯字符串匹配，漏一种就等于那种浏览器上整个不生效。**
 * 漏掉的表现和没写这个处理一模一样（点菜单没反应），不报错、不告警。四种：
 *   - Chromium：`Failed to fetch dynamically imported module: <url>`
 *   - Firefox： `error loading dynamically imported module: <url>`  ← 曾经漏掉
 *   - Safari：  `Importing a module script failed.`
 *   - Vite 的 CSS 预加载助手：`Unable to preload CSS for <url>`     ← 曾经漏掉
 * `ChunkLoadError` 是 webpack 的叫法，本项目用 Vite 不会产生，留着不花钱。
 *
 * 匹配一律小写后比对：同一句话在不同版本里首字母大小写变过（`Failed` / `failed`），
 * 而这种差别造成的漏判同样是静默的。
 */
const CHUNK_ERROR_PATTERNS = [
  'failed to fetch dynamically imported module',
  'error loading dynamically imported module',
  'importing a module script failed',
  'unable to preload css for'
]

export function isChunkLoadError(error: unknown): boolean {
  if (!error) return false

  // 守卫里 throw 一个字符串、或第三方抛出非 Error 对象都是可能的。
  // 直接 `error.message.includes(...)` 会在**错误处理器内部**再抛一个 TypeError，
  // 把原本只是"这一次导航失败"的问题升级成整个 onError 失效。
  const name = typeof (error as any)?.name === 'string' ? (error as any).name : ''
  if (name === 'ChunkLoadError') return true

  const raw = typeof (error as any)?.message === 'string'
    ? (error as any).message
    : typeof error === 'string'
      ? error
      : ''
  if (!raw) return false

  const message = raw.toLowerCase()
  return CHUNK_ERROR_PATTERNS.some((pattern) => message.includes(pattern))
}
