# PT 订阅/下载记录页样式打磨 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** `ptDownloadRecord`/`ptSubscription` 两个 PC 端页面——失败卡片在网格里一眼可辨、首次加载展示同构骨架屏而不是空白转圈、`<style>` 块里散落的 `el-color-*`/`el-fill-color-*`/`el-text-color-*` 全部换成 `--osr-*` 令牌。

**架构：** 纯前端模板 class 绑定 + `<style>` 改动，不改 composable、不改 API、不改数据模型、不新增依赖。`card-grid` 拆成"骨架屏分支（`loading && taskList.length===0`）+ 现有真实网格分支（`v-else v-loading`）"两个同级 `v-if`/`v-else`，两个分支共用同一个 `.card-grid` 类保证列数/间距一致。骨架屏用 Element Plus 自带的 `<el-skeleton>`/`<el-skeleton-item>`（`unplugin-vue-components` 自动导入，首次使用后 `components.d.ts` 会自动新增两条声明）。

**技术栈：** Vue 3 + Element Plus 2.6 + SCSS；Vitest + @vue/test-utils（组件级挂载测试，mock 掉 composable 避免真实网络请求）。

---

## 前置说明

- 本计划严格对照 `docs/superpowers/specs/2026-07-24-pt-style-polish-design.md` 编写。实现前已完整读过以下真实源码并逐行核对：`openlist-web/src/views/openlist/ptDownloadRecord/index.vue`（306 行）、`openlist-web/src/views/openlist/ptSubscription/index.vue`（625 行）、`openlist-web/src/styles/tokens.scss`、`openlist-web/src/composables/usePtDownloadRecord.ts`、`openlist-web/src/composables/usePtSubscription.ts`、既有单测 `src/components/__tests__/SidebarMenuItem.spec.ts`、`src/composables/__tests__/usePtDownloader.spec.ts`、`openlist-web/vitest.config.ts`、`openlist-web/vite.config.ts`、`openlist-web/tsconfig.json`、`openlist-web/package.json`、`openlist-web/src/components.d.ts`。下面每个任务给出的模板/样式片段、行号、测试代码，均已在本仓库当前 worktree 里实际跑过 `npx vitest run`、`npx vue-tsc --noEmit`、`npx eslint`、`npx vite build` 全部通过后再回退，不是凭空写的。
- **组件级测试的关键陷阱（务必先读完再动手）：**
  1. `usePtDownloadRecord()`/`usePtSubscription()` 在 setup 阶段就同步调用一次 `getList()` 发真实网络请求。挂载真实页面组件做单测时必须 `vi.mock` 掉整个组合式函数模块，直接控制返回值，否则会触发真实 axios 请求（测试环境里会挂起/报错）。
  2. mock 返回值里 `taskList`/`loading` 等必须用真正的 `ref()`（从 `vue` 导入），**不能**手写 `{ value: [...] }` 这种普通对象——`<script setup>` 模板的自动解包（`_unref`）靠 Vue 内部的 `__v_isRef` 标记识别，手搓对象没有这个标记，`v-for="item in taskList"` 会把它当成"只有一个 `value` 属性的普通对象"来遍历（渲染出一张字段全是空/`-` 的卡片），而不是遍历数组本身。这是本计划里最容易踩的坑，务必照抄下面测试代码里的写法。
  3. 本项目 `vitest.config.ts` 故意不加载 `unplugin-vue-components`/`unplugin-auto-import`（避免测试环境噪音，见该文件注释），因此模板里的 `el-card`/`el-button`/`Search` 等标签在测试环境里解析不到真实 Element Plus 实现。Vue 对无法解析的标签的默认行为是**退化成普通 DOM 元素**渲染（只有控制台一条 `[Vue warn] Failed to resolve component` 警告，不会报错、不会中断渲染），`class`/`v-if`/`v-for` 等结构性断言完全不受影响，**不需要为每个 el-\* 标签写 stub**。
  4. 唯一的例外是**带 scoped slot 的组件**（本计划里是 `ptSubscription/index.vue` 弹窗里的 `el-table`/`el-table-column`，用了 `<template #default="scope">`）：退化成普通元素后，Vue 会直接同步调用一次该 slot 函数且不传参，导致 `scope` 是 `undefined`、`scope.row` 报错并让整个挂载失败。凡是挂载 `ptSubscription/index.vue` 的测试，必须显式 stub 掉这三个标签（`global: { stubs: { 'el-dialog': true, 'el-table': true, 'el-table-column': true } }`），下面任务 4 的测试代码已经这样写。
  5. `ptSubscription/index.vue` 在 `<script setup>` 里直接调用 `useRouter()`（不是通过 composable），测试环境没装 `vue-router` 插件，需要 `vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))`。
  6. 本项目没装 `@types/node`（`tsconfig.json` 也没配置 node 类型），任务 3 的令牌替换测试要读源文件文本（`node:fs`），会被 `vue-tsc`（走 `tsconfig.json` 的 `src/**/*.ts` 范围）报 `TS2307 Cannot find module 'node:fs'`。这类测试文件顶部要加 `// @ts-nocheck` 跳过类型检查（vitest 运行时是真实 Node.js，类型声明缺失不影响运行，只影响 `vue-tsc`），已在任务 3 的测试代码里写好，不要漏掉。
- 任务 1→2 都改 `ptDownloadRecord/index.vue`，**必须按顺序执行**：任务 2 的"修改"行号是任务 1 完成后的文件行号。任务 3→4 都改 `ptSubscription/index.vue`，同样必须按顺序执行，任务 4 的行号是任务 3 完成后的文件行号。ptDownloadRecord（任务 1-2）与 ptSubscription（任务 3-4）之间互不依赖，理论上可以交换顺序，但本计划按"先做完一个文件、再做另一个"排列，方便增量跑测试。
- 任务 2 是本计划第一次在模板里用到 `<el-skeleton>`/`<el-skeleton-item>`，`npx vite build`（走真实 `vite.config.ts`，带 `unplugin-vue-components` + `ElementPlusResolver`）执行时会自动往 `openlist-web/src/components.d.ts` 追加 `ElSkeleton`/`ElSkeletonItem` 两行声明并重新排序（该文件已提交进 git，此前 `SidebarMenuItem` 组件上线时也有过同样的自动生成 + 提交，见 commit `9e720a54`）。任务 2 的步骤里会运行一次 `npm run build` 来触发这次自动生成，并把 `components.d.ts` 的变更一并提交。
- 所有命令均在 `openlist-web/` 目录下执行（本计划里的相对路径命令都假定当前目录是 `openlist-web`）。

---

### 任务 1：ptDownloadRecord 失败卡片视觉强化 + `.record-fail` 令牌替换

**文件：**
- 修改：`openlist-web/src/views/openlist/ptDownloadRecord/index.vue:39`（模板，`.record-card` 增加条件 class）、`:196-209`（样式，`.record-card` 块后新增 `.record-card--failed`）、`:263-273`（样式，重写 `.record-fail`）
- 创建：`openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

- [ ] **步骤 1：编写失败的测试**

创建 `openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`：

```ts
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, reactive } from 'vue'

// usePtDownloadRecord 在 setup 阶段就同步调用 getList() 发真实请求，
// mock 掉整个组合式函数，避免真实网络请求，并直接控制 taskList/loading。
vi.mock('@/composables/usePtDownloadRecord', () => ({
  usePtDownloadRecord: vi.fn()
}))

import PtDownloadRecordPage from '../index.vue'
import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'

/**
 * 组合式函数返回值必须用真正的 ref()/reactive()，不能用 { value: ... } 这种手搓对象——
 * <script setup> 模板里的自动解包（_unref）依赖 Vue 内部的 __v_isRef 标记，
 * 手搓对象拿不到这个标记，v-for 会把它当成一个只有 value 属性的普通对象来遍历，
 * 而不是遍历 value 里的数组。
 */
function baseComposable(overrides: Record<string, any> = {}) {
  return {
    taskList: ref([]),
    loading: ref(false),
    total: ref(0),
    queryParams: reactive({ pageNum: 1, pageSize: 10 }),
    getList: vi.fn(),
    handleQuery: vi.fn(),
    resetQuery: vi.fn(),
    queryRef: ref(null),
    retryingIds: reactive(new Set()),
    handleRetry: vi.fn(),
    ...overrides
  }
}

describe('PtDownloadRecord 失败卡片视觉强化', () => {
  it('FAILED 状态的卡片带有 record-card--failed 类', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const card = wrapper.find('.record-card')
    expect(card.classes()).toContain('record-card--failed')
  })

  it('非 FAILED 状态的卡片不带 record-card--failed 类', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 2, title: 'B', state: 'COMPLETED' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const card = wrapper.find('.record-card')
    expect(card.classes()).not.toContain('record-card--failed')
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

预期：2 个用例中 1 个 FAIL——`FAILED 状态的卡片带有 record-card--failed 类` 报错 `AssertionError: expected [ 'record-card' ] to include 'record-card--failed'`；`非 FAILED 状态的卡片不带 record-card--failed 类` 因为当前代码本来就没有这个 class，会直接 PASS（这是正常的，它是给之后的实现做的回归防护，不是本步骤要修的红灯）。

- [ ] **步骤 3：编写最少实现代码**

编辑 `openlist-web/src/views/openlist/ptDownloadRecord/index.vue`。

模板部分（第 39 行，只改这一行，先不动 38 行的 `v-loading` 外层 div——那是任务 2 的范围）：

```html
        <div v-for="item in taskList" :key="item.id" class="record-card">
```
替换为：
```html
        <div
          v-for="item in taskList"
          :key="item.id"
          class="record-card"
          :class="{ 'record-card--failed': item.state === 'FAILED' }"
        >
```

样式部分（第 196-209 行 `.record-card` 块之后，新增 `.record-card--failed`）：

```scss
.record-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }
}

.record-card--failed {
  border-left: 3px solid var(--osr-danger);
  padding-left: 13px;
}
```

样式部分（第 263-273 行，整块替换 `.record-fail`）：

原内容：
```scss
.record-fail {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 10px;
  border-radius: var(--osr-radius-sm);
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.5;
}
```
替换为：
```scss
.record-fail {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-danger-light);
  color: var(--osr-danger);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;

  .el-icon {
    font-size: 16px;
    flex-shrink: 0;
  }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

预期：`Test Files 1 passed (1)`、`Tests 2 passed (2)`。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptDownloadRecord/index.vue openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts
git commit -m "style: PT下载记录失败卡片左侧色条强化，record-fail 令牌替换"
```

---

### 任务 2：ptDownloadRecord 骨架屏

**文件：**
- 修改：`openlist-web/src/views/openlist/ptDownloadRecord/index.vue:38`（模板起点，`card-grid` 拆成骨架屏分支 + 真实网格分支，任务 1 完成后这一行仍是 `<div class="card-grid" v-loading="loading">`，未被任务 1 触碰）、`:216-219`（样式，任务 1 完成后 `.record-card--failed` 块的实际位置，动手前务必用 Read 重新确认——行号会因为任务 1 的模板改动量而漂移）
- 修改：`openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`（在文件末尾追加一个新的 `describe` 块）

- [ ] **步骤 1：编写失败的测试**

在 `openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts` 文件末尾（任务 1 留下的最后一个 `})` 之后）追加：

```ts

describe('PtDownloadRecord 骨架屏', () => {
  it('首次加载（loading 且列表为空）渲染 6 张骨架卡片，不渲染真实卡片', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.findAll('.record-card-skeleton').length).toBe(6)
    expect(wrapper.find('.record-card').exists()).toBe(false)
  })

  it('已有数据时重新查询（loading 且列表非空）不回退成骨架屏', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }]),
      loading: ref(true)
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-card-skeleton').exists()).toBe(false)
    expect(wrapper.find('.record-card').exists()).toBe(true)
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

预期：4 个用例中 1 个 FAIL——`首次加载（loading 且列表为空）渲染 6 张骨架卡片，不渲染真实卡片` 报错 `AssertionError: expected +0 to be 6`（`.record-card-skeleton` 现在还不存在）；`已有数据时重新查询...不回退成骨架屏` 因为当前代码本来就是无条件渲染真实卡片，会直接 PASS（同样是给之后实现做的回归防护）。

- [ ] **步骤 3：编写最少实现代码**

编辑 `openlist-web/src/views/openlist/ptDownloadRecord/index.vue`。

模板部分，把（任务 1 完成后的）：
```html
      <div class="card-grid" v-else v-loading="loading">
        <div
          v-for="item in taskList"
          :key="item.id"
          class="record-card"
          :class="{ 'record-card--failed': item.state === 'FAILED' }"
        >
```
（注意这里第一行原本是 `<div class="card-grid" v-loading="loading">`，任务 1 没有动它——现在要在它前面插入骨架屏分支，并把它自己改成 `v-else`）替换为：
```html
      <div class="card-grid" v-if="loading && taskList.length === 0">
        <div v-for="n in 6" :key="n" class="record-card-skeleton">
          <el-skeleton animated>
            <template #template>
              <el-skeleton-item variant="text" style="width: 60%; height: 16px; margin-bottom: 10px" />
              <el-skeleton-item variant="text" style="width: 40%; margin-bottom: 10px" />
              <el-skeleton-item variant="text" style="width: 100%; margin-bottom: 6px" />
              <el-skeleton-item variant="text" style="width: 100%; margin-bottom: 6px" />
              <el-skeleton-item variant="text" style="width: 80%" />
            </template>
          </el-skeleton>
        </div>
      </div>
      <div class="card-grid" v-else v-loading="loading">
        <div
          v-for="item in taskList"
          :key="item.id"
          class="record-card"
          :class="{ 'record-card--failed': item.state === 'FAILED' }"
        >
```

样式部分，在 `.record-card--failed` 块之后新增：

```scss
.record-card-skeleton {
  --el-skeleton-color: var(--osr-border-light);
  --el-skeleton-to-color: var(--osr-bg-page);
  padding: 14px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
}
```

- [ ] **步骤 4：运行测试验证通过，并触发 components.d.ts 自动生成**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

预期：`Test Files 1 passed (1)`、`Tests 4 passed (4)`。

再运行一次全量构建（这是本计划第一次用到 `<el-skeleton>`，触发 `unplugin-vue-components` 往 `src/components.d.ts` 自动追加 `ElSkeleton`/`ElSkeletonItem` 声明）：

运行：`cd openlist-web && npm run build`

预期：退出码 0，末尾输出 `✓ built in` 与 PWA 的 `files generated`；`git diff --stat -- src/components.d.ts` 能看到 2 行新增（`ElSkeleton`/`ElSkeletonItem`）。构建产物目录 `dist/` 不需要提交，下一步 `git add` 不会包含它（未在 `git status` 里出现是正常的，`.gitignore` 已排除）。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptDownloadRecord/index.vue openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts openlist-web/src/components.d.ts
git commit -m "feat: PT下载记录首次加载展示骨架屏，替代空白转圈"
```

---

### 任务 3：ptSubscription 令牌替换（`.sub-year`/`.picked-bar`/`.all-done`）

**文件：**
- 修改：`openlist-web/src/views/openlist/ptSubscription/index.vue:583-586`（`.sub-year`）、`:588-593`（`.picked-bar`）、`:601-603`（`.all-done`）
- 创建：`openlist-web/src/views/openlist/ptSubscription/__tests__/style-tokens.spec.ts`

**背景**：这三处是纯 CSS 自定义属性名替换，替换前后视觉表现不变（`--osr-*` 当前取值与被替换的 `el-*` 变量取值相同，这本就是"前置修复"的含义——见设计文档 2.3 节）。jsdom 不会真正计算 `var(--xxx)` 的渲染值，挂载组件断言不出"用了哪个变量"，因此这类改动唯一有意义的自动化验证方式是直接对源码文本做正则断言，断言"引用的变量名"而不是"渲染出的颜色"。

- [ ] **步骤 1：编写失败的测试**

创建 `openlist-web/src/views/openlist/ptSubscription/__tests__/style-tokens.spec.ts`：

```ts
// @ts-nocheck
// 项目没装 @types/node（tsconfig 也没配置 node 类型），下面几个 node: 内置模块在
// vue-tsc（走 tsconfig 的 src/**/*.ts 范围）里会报 TS2307 找不到类型声明；
// 运行时 vitest 用真实 Node.js 执行，模块能正常解析，只是类型层面没声明，
// 用 @ts-nocheck 跳过本文件的类型检查，不需要为了一个测试文件新增依赖。
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 用 dirname(fileURLToPath(import.meta.url)) 而不是 new URL('../index.vue', import.meta.url)：
 * 后者在本仓库 Windows + Vitest 环境下会报 "The URL must be of scheme file"。
 */
const currentFile = fileURLToPath(import.meta.url)
const filePath = join(dirname(currentFile), '../index.vue')
const source = readFileSync(filePath, 'utf-8')

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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/style-tokens.spec.ts`

预期：3 个用例全部 FAIL，报错分别是：
- `.sub-year 使用 --osr-text-secondary 而不是 --el-text-color-secondary` → 第一个 `expect(...).not.toMatch(...)` 断言失败（当前确实用了 `--el-text-color-secondary`）
- `.picked-bar 使用 --osr-bg-page 而不是 --el-fill-color-light` → 同理，当前用了 `--el-fill-color-light`
- `.all-done 使用 --osr-success 而不是 --el-color-success` → 同理，当前用了 `--el-color-success`

- [ ] **步骤 3：编写最少实现代码**

编辑 `openlist-web/src/views/openlist/ptSubscription/index.vue`，第 583-586 行、588-593 行：

原内容：
```scss
.sub-year {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.picked-bar {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}
```
替换为：
```scss
.sub-year {
  color: var(--osr-text-secondary);
  font-size: 12px;
}

.picked-bar {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 4px;
  background: var(--osr-bg-page);
}
```

第 601-603 行：

原内容：
```scss
.all-done {
  color: var(--el-color-success);
}
```
替换为：
```scss
.all-done {
  color: var(--osr-success);
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/style-tokens.spec.ts`

预期：`Test Files 1 passed (1)`、`Tests 3 passed (3)`。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptSubscription/index.vue openlist-web/src/views/openlist/ptSubscription/__tests__/style-tokens.spec.ts
git commit -m "style: PT订阅页 sub-year/picked-bar/all-done 改用 --osr-* 令牌"
```

---

### 任务 4：ptSubscription 骨架屏

**文件：**
- 修改：`openlist-web/src/views/openlist/ptSubscription/index.vue:47-48`（模板，`card-grid` 拆成骨架屏分支 + 真实网格分支，行号基于任务 3 完成后的文件——任务 3 只改了 583 行之后的样式，不影响这里的行号）、`.sub-card` 样式块（任务 3 完成后仍在第 441-453 行左右）之后新增 `.sub-card-skeleton`
- 创建：`openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts`

- [ ] **步骤 1：编写失败的测试**

创建 `openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts`：

```ts
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, reactive } from 'vue'

// 页面 <script setup> 里直接调用 useRouter()，测试环境没有安装 vue-router 插件，
// mock 掉整个模块避免路由相关报错。
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
}))

// usePtSubscription 在 setup 阶段同步调用 base.getList() 发真实请求，
// mock 掉整个组合式函数，避免真实网络请求，并直接控制 taskList/loading。
vi.mock('@/composables/usePtSubscription', () => ({
  usePtSubscription: vi.fn()
}))

import PtSubscriptionPage from '../index.vue'
import { usePtSubscription } from '@/composables/usePtSubscription'

/**
 * usePtSubscription 展开了一长串 dialog/表单相关状态和方法，页面模板里都会用到
 * （即使本文件只关心 card-grid 分支），全部给够避免挂载时读 undefined.value 报错。
 * el-dialog/el-table/el-table-column 显式 stub 掉：这三个组件内部用 scoped slot
 * （el-table-column 的 #default="scope"）传行数据，测试环境没有注册真正的
 * Element Plus，未知组件会退化成普通 DOM 元素，普通元素遇到 scoped slot 对象会
 * 直接同步调用一次 slot 函数（不传参），导致 scope 是 undefined、scope.row 报错。
 * stub 之后这三个标签整体替换成空标记，跳过内部内容渲染，规避这个陷阱。
 */
function baseComposable(overrides: Record<string, any> = {}) {
  return {
    taskList: ref([]),
    loading: ref(false),
    total: ref(0),
    queryParams: reactive({ pageNum: 1, pageSize: 10 }),
    getList: vi.fn(),
    handleQuery: vi.fn(),
    resetQuery: vi.fn(),
    queryRef: ref(null),
    subscribeOpen: ref(false),
    searchLoading: ref(false),
    subscribeLoading: ref(false),
    searchResults: ref([]),
    searchForm: reactive({ mediaType: 'TV', keyword: '' }),
    picked: ref(null),
    pickedSeason: ref(1),
    openSubscribeDialog: vi.fn(),
    doSearch: vi.fn(),
    pick: vi.fn(),
    confirmSubscribe: vi.fn(),
    progressOpen: ref(false),
    progressLoading: ref(false),
    progress: ref(null),
    currentSubscription: ref(null),
    showProgress: vi.fn(),
    searchLogOpen: ref(false),
    searchLogLoading: ref(false),
    searchLogs: ref([]),
    showSearchLogs: vi.fn(),
    filterOverrideOpen: ref(false),
    filterOverrideSaving: ref(false),
    filterOverrideForm: reactive({
      minSeeders: { enabled: false, value: 1 },
      minSize: { enabled: false, value: 0 },
      maxSize: { enabled: false, value: 0 },
      freeOnly: { enabled: false, value: '0' },
      includeKeywords: { enabled: false, value: '' },
      excludeKeywords: { enabled: false, value: '' },
      resolutionWhitelist: { enabled: false, value: '' },
      resolutionPriority: { enabled: false, value: '' },
      preferredSize: { enabled: false, value: 0 }
    }),
    openFilterOverride: vi.fn(),
    saveFilterOverride: vi.fn(),
    searchDialogOpen: ref(false),
    searchDialogLoading: ref(false),
    searchDialogKeyword: ref(''),
    openSeasonSearch: vi.fn(),
    openEpisodeSearch: vi.fn(),
    confirmSearch: vi.fn(),
    toggleAutoSearch: vi.fn(),
    handleRefresh: vi.fn(),
    handlePause: vi.fn(),
    handleResume: vi.fn(),
    handleRemove: vi.fn(),
    ...overrides
  }
}

const mountOptions = {
  global: { stubs: { 'el-dialog': true, 'el-table': true, 'el-table-column': true } }
}

describe('PtSubscription 骨架屏', () => {
  it('首次加载（loading 且列表为空）渲染 6 张骨架卡片，不渲染真实卡片', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.findAll('.sub-card-skeleton').length).toBe(6)
    expect(wrapper.find('.sub-card').exists()).toBe(false)
  })

  it('已有数据时重新查询（loading 且列表非空）不回退成骨架屏', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.sub-card-skeleton').exists()).toBe(false)
    expect(wrapper.find('.sub-card').exists()).toBe(true)
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/index.spec.ts`

预期：2 个用例中 1 个 FAIL——`首次加载（loading 且列表为空）渲染 6 张骨架卡片，不渲染真实卡片` 报错 `AssertionError: expected +0 to be 6`（`.sub-card-skeleton` 现在还不存在）；`已有数据时重新查询...不回退成骨架屏` 因为当前代码本来就是无条件渲染真实卡片，会直接 PASS。

- [ ] **步骤 3：编写最少实现代码**

编辑 `openlist-web/src/views/openlist/ptSubscription/index.vue`。

模板部分，把：
```html
      <div class="card-grid" v-loading="loading">
        <div v-for="item in taskList" :key="item.id" class="sub-card">
```
替换为：
```html
      <div class="card-grid" v-if="loading && taskList.length === 0">
        <div v-for="n in 6" :key="n" class="sub-card-skeleton">
          <el-skeleton animated class="sub-card-skeleton__body">
            <template #template>
              <el-skeleton-item variant="image" class="sub-card-skeleton__poster" />
              <div class="sub-card-skeleton__info">
                <el-skeleton-item variant="text" style="width: 70%; height: 16px; margin-bottom: 10px" />
                <el-skeleton-item variant="text" style="width: 50%; margin-bottom: 10px" />
                <el-skeleton-item variant="text" style="width: 100%; margin-bottom: 6px" />
                <el-skeleton-item variant="text" style="width: 100%" />
              </div>
            </template>
          </el-skeleton>
        </div>
      </div>
      <div class="card-grid" v-else v-loading="loading">
        <div v-for="item in taskList" :key="item.id" class="sub-card">
```

样式部分，在 `.sub-card { ... }` 块之后新增：

```scss
.sub-card-skeleton {
  --el-skeleton-color: var(--osr-border-light);
  --el-skeleton-to-color: var(--osr-bg-page);
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);

  :deep(.sub-card-skeleton__body) {
    display: flex;
    gap: 12px;
  }

  :deep(.sub-card-skeleton__poster) {
    flex-shrink: 0;
    width: 72px;
    height: 108px;
    border-radius: var(--osr-radius-sm);
  }

  :deep(.sub-card-skeleton__info) {
    flex: 1;
    min-width: 0;
    padding-top: 2px;
  }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/index.spec.ts`

预期：`Test Files 1 passed (1)`、`Tests 2 passed (2)`。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptSubscription/index.vue openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts
git commit -m "feat: PT订阅页首次加载展示骨架屏，替代空白转圈"
```

---

### 任务 5：整体回归验证与手动视觉走查（无新代码变更）

**文件：** 无新增/修改文件，本任务只做验证，不改动实现代码。

**背景**：任务 1-4 各自的单测只覆盖各自新增的结构性断言（class/DOM 节点数量/源码文本），不能替代"整个页面在真实浏览器里长什么样"的确认——尤其是骨架屏的 `<el-skeleton>` 渐变动画、失败卡片色条与其余内容的间距，都是纯视觉效果，jsdom 环境断言不出来。这一步对齐设计文档第 7 节"测试计划"，也是本项目对纯样式改动一贯的验收方式（`SidebarMenuItem`/菜单分类相关提交同样是"改完让流水线跑绿 + 人工过一遍视觉"）。

- [ ] **步骤 1：跑全量单元测试**

运行：`cd openlist-web && npx vitest run`

预期：全部 Test Files/Tests 通过，其中应包含本计划新增的 3 个测试文件：`ptDownloadRecord/__tests__/index.spec.ts`（4 用例）、`ptSubscription/__tests__/style-tokens.spec.ts`（3 用例）、`ptSubscription/__tests__/index.spec.ts`（2 用例），加上仓库原有的 3 个测试文件（`SidebarMenuItem.spec.ts`/`usePtDownloader.spec.ts`/`useTaskList.spec.ts`，共 12 用例），合计 6 个测试文件、21 个用例。

- [ ] **步骤 2：跑类型检查与完整构建**

运行：`cd openlist-web && npm run build`

预期：退出码 0，输出以 `✓ built in` 结尾（`vue-tsc` 无类型错误，`vite build` 产物写入 `dist/`，PWA 插件生成 `sw.js`）。这一步同时验证任务 2/4 引入的 `v-if`/`v-else` 模板分支没有语法或类型错误。

- [ ] **步骤 3：跑 ESLint**

运行：`cd openlist-web && npm run lint`

预期：退出码 0，无残留错误（`--fix` 会自动修正可修复的格式问题，若有改动需要 `git add` 后一并提交，但本计划的代码已按项目现有风格书写，预期无需自动修正）。

- [ ] **步骤 4：手动视觉走查清单**

启动本地开发环境（`cd openlist-web && npm run dev`，配合已运行的后端 `localhost:6895`），登录后依次确认：

- **失败卡片**：进入 `PT下载记录` 页面，找一条 `state=FAILED` 的记录（没有真实数据时可以在浏览器 DevTools 里临时把某条记录的 state 改成 FAILED 观察，或直接查库造一条）——左侧应出现 3px 红色竖条，`.record-fail` 提示条文字加粗、图标变大且垂直居中；同网格里非失败卡片的外观、内容对齐不受影响（`padding-left` 补偿没有让内容整体错位）。
- **骨架屏（下载记录页）**：强刷新 `PT下载记录` 页面，网格短暂展示 6 张骨架卡片形状（不是空白矩形转圈），数据回来后骨架屏消失、真实卡片渲染，两者切换时网格列数/间距不跳动。之后点分页或改搜索条件重新查询，网格应保持现有的半透明遮罩 + 转圈图标叠加效果（不应该退化成再次出现骨架屏）。搜索结果为 0 条时，`el-empty` 正常展示。
- **骨架屏（订阅页）**：同样强刷新 `PT订阅管理` 页面重复以上三点，额外确认骨架卡片里 72×108 的海报占位块位置、大小与真实卡片的 `.sub-poster` 一致。
- **令牌替换视觉一致性**：对比改动前后的截图（或直接目测当前唯一存在的浅色主题），确认 `.record-fail`（下载记录页）、`.picked-bar`/`.sub-year`/`.all-done`（订阅页"新增订阅"弹窗的已选提示条、年份小字、订阅进度弹窗的"全部集已入库"提示）四处颜色与改动前一致，没有因为令牌取值偏差而变色。

以上四项全部符合预期后，本次改动视为完成；无需额外 commit（本任务不产生代码变更）。
