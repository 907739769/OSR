// eslint-disable-next-line @typescript-eslint/ban-ts-comment -- 项目没装 @types/node，下面用到的 node: 内置模块在 vue-tsc 下无类型声明，需要 @ts-nocheck 跳过本文件类型检查
// @ts-nocheck
// 项目没装 @types/node（tsconfig 也没配置 node 类型），下面几个 node: 内置模块在
// vue-tsc（走 tsconfig 的 src/**/*.ts 范围）里会报 TS2307 找不到类型声明；
// 运行时 vitest 用真实 Node.js 执行，模块能正常解析，只是类型层面没声明，
// 用 @ts-nocheck 跳过本文件的类型检查，不需要为了一个测试文件新增依赖。
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 用 dirname(fileURLToPath(import.meta.url)) 而不是 new URL('../index.vue', import.meta.url)：
 * 后者在本仓库 Windows + Vitest 环境下会报 "The URL must be of scheme file"。
 */
const currentFile = fileURLToPath(import.meta.url)
const pageDir = join(dirname(currentFile), '..')

/**
 * 读整个页面目录而不是单个 index.vue：订阅页已经拆成
 * index.vue + SubscriptionCard.vue + dialogs/*.vue，被检查的那几条样式散在子组件里。
 * 只读 index.vue 的话，这些断言会在「文件里根本没有 .sub-year」时静默变成永远失败/永远通过。
 */
function readAllVue(dir: string): string {
  return readdirSync(dir, { withFileTypes: true })
    .flatMap((entry) => {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) return entry.name === '__tests__' ? [] : [readAllVue(full)]
      return entry.name.endsWith('.vue') ? [readFileSync(full, 'utf-8')] : []
    })
    .join('\n')
}

const source = readAllVue(pageDir)

describe('PtSubscription 令牌替换：不再直接引用 el-* 原生变量', () => {
  it('.sub-year 使用 --osr-text-secondary 而不是 --el-text-color-secondary', () => {
    expect(source).not.toMatch(/\.sub-year\s*\{[^}]*--el-text-color-secondary/)
    expect(source).toMatch(/\.sub-year\s*\{[^}]*--osr-text-secondary/)
  })

  it('.picked-bar 使用 --osr-bg-page 而不是 --el-fill-color-light', () => {
    expect(source).not.toMatch(/\.picked-bar\s*\{[^}]*--el-fill-color-light/)
    expect(source).toMatch(/\.picked-bar\s*\{[^}]*--osr-bg-page/)
  })

  it('.all-done 使用 --osr-success 而不是 --el-color-success', () => {
    expect(source).not.toMatch(/\.all-done\s*\{[^}]*--el-color-success/)
    expect(source).toMatch(/\.all-done\s*\{[^}]*--osr-success/)
  })
})
