import { describe, it, expect } from 'vitest'
import { isChunkLoadError } from '../chunkError'

describe('isChunkLoadError 覆盖各家浏览器的措辞', () => {
  // 这四条是这个文件存在的全部理由：判据是纯字符串匹配，漏一种措辞就等于
  // 那种浏览器上整个 chunk 兜底不生效，而表现和没写这段代码一模一样（点菜单没反应），
  // 不报错也不告警。Firefox 与 CSS 预加载这两条最初就是漏掉的。
  const cases: Array<[string, string]> = [
    ['Chromium', 'Failed to fetch dynamically imported module: https://osr/assets/index-BjWFPVqh.js'],
    ['Firefox', 'error loading dynamically imported module: https://osr/assets/index-BjWFPVqh.js'],
    ['Safari', 'Importing a module script failed.'],
    ['Vite CSS 预加载', 'Unable to preload CSS for /assets/index-DNaMq7sd.css']
  ]

  it.each(cases)('认得出 %s 的报错', (_browser, msg) => {
    expect(isChunkLoadError(new Error(msg))).toBe(true)
  })

  it('大小写变化不影响判定', () => {
    // 同一句话在不同版本里首字母大小写变过，而这种差别造成的漏判同样是静默的
    expect(isChunkLoadError(new Error('FAILED TO FETCH DYNAMICALLY IMPORTED MODULE: /a.js'))).toBe(true)
  })

  it('认得出 webpack 的 ChunkLoadError（按 name 而不是 message）', () => {
    const err = new Error('Loading chunk 42 failed.')
    err.name = 'ChunkLoadError'
    expect(isChunkLoadError(err)).toBe(true)
  })
})

describe('isChunkLoadError 不误伤其它错误', () => {
  it('普通业务异常不算', () => {
    expect(isChunkLoadError(new Error('请求超时'))).toBe(false)
  })

  it('守卫里抛出的鉴权错误不算', () => {
    expect(isChunkLoadError(new Error('Invalid navigation guard'))).toBe(false)
  })
})

describe('isChunkLoadError 对非 Error 输入是安全的', () => {
  // 直接 error.message.includes(...) 会在**错误处理器内部**再抛一个 TypeError，
  // 把"这一次导航失败"升级成整个 onError 失效——后续任何 chunk 失效都不再有人兜底
  it.each([
    ['null', null],
    ['undefined', undefined],
    ['数字', 42],
    ['空对象', {}],
    ['message 不是字符串', { message: { nested: true } }]
  ])('%s 不抛异常且判为 false', (_label, input) => {
    expect(() => isChunkLoadError(input)).not.toThrow()
    expect(isChunkLoadError(input)).toBe(false)
  })

  it('直接 throw 字符串时仍能识别', () => {
    expect(isChunkLoadError('Failed to fetch dynamically imported module: /a.js')).toBe(true)
  })
})
