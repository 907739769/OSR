# PT 订阅/下载记录页样式打磨设计（失败态 / 骨架屏 / 深色模式排查）

**日期**：2026-07-24
**作者**：Jack（与 Claude 协作）
**前置阅读**：
- `openlist-web/src/AGENTS.md`（CSS 变量约定：统一用 `--osr-*` 前缀令牌）
- `openlist-web/src/styles/tokens.scss`（`--osr-*` 令牌定义）、`openlist-web/src/styles/index.scss`（Element Plus 全局覆盖）
- `openlist-web/src/views/openlist/ptSubscription/index.vue`、`openlist-web/src/views/openlist/ptDownloadRecord/index.vue`（本次改动对象）

**范围声明**：纯样式改动（模板 class 绑定 + `<style>` 块），不改 composable 逻辑、不改 API、不改数据模型、不新增依赖。

## 1. 背景与目标

### 1.1 现状

`ptDownloadRecord/index.vue` 和 `ptSubscription/index.vue` 都是"卡片网格"布局（`card-grid` + `v-for` 卡片），共享同一套写法：

- 失败态：`.record-fail` 只是卡片内一条小字背景条（`el-color-danger-light-9` 背景 + 图标 + 文字），卡片外观和正常卡片没有区别，网格里扫一眼看不出哪些失败。
- 加载态：`v-loading="loading"` 直接盖在 `card-grid` 上，首次加载时网格是空的（`min-height: 120px` 撑住），用户看到的是一个只有转圈图标的空白矩形，直到数据回来才第一次出现卡片形状。
- 颜色令牌：两个文件的 `<style>` 块里混用了 `--osr-*` 令牌和 Element Plus 原生变量（`--el-color-danger-light-9`、`--el-fill-color-light`、`--el-text-color-secondary`、`--el-color-success`），不完全遵守 `AGENTS.md` "CSS 变量统一用 `--osr-*`" 的约定。

### 1.2 一个前置事实：项目目前没有深色模式

对全仓库 `src/` 做过 grep（`prefers-color-scheme`、`data-theme`、`matchMedia`、主题 store/toggle），**零命中**。`tokens.scss` 里的 `:root` 只有一套值，没有任何 dark 分支；`index.scss`、任何 store、任何 layout 组件都不存在明暗切换逻辑。也就是说：

- 当前无论系统/浏览器是明是暗，页面渲染出来的都是同一套浅色 UI（`--osr-bg-page: #f1f5f9` 等值是硬写在 `:root` 里的，没有任何机制会切换它们）。
- "深色模式下对比度不足"这个问题现在**不会真的在界面上发生**，因为深色模式根本不存在——它是一个还没建立的能力，不是一个已经出问题的能力。

这个发现直接决定了第 2.3 节的设计边界：本次任务名为"样式打磨"，不是"新增深色模式"，给整个应用引入一套深色调色板 + 切换机制（`prefers-color-scheme` 全局响应或手动 toggle store）是一个覆盖全站导航栏/侧边栏/表格/弹窗的独立课题，远超"PT 两个页面"的范围，也不是三项任务里要求的东西。本次要做的、且做了就有实际价值的事情是：**把这两个文件里绕开 `--osr-*` 令牌、直接写 `el-color-*`/`el-fill-color-*`/`el-text-color-*` 的地方改回令牌**——这是深色模式的必要前置工作（令牌不统一，将来定义深色值时这两个文件根本不会跟着变），且在深色模式落地之前就已经是纯粹的代码规范收益（消除两套颜色体系混用）。真正定义每个 `--osr-*` 令牌的深色取值、以及用什么机制激活深色模式，作为独立后续课题列在第 8 节"不做的事情"里。

### 1.3 目标

1. 下载记录网格里，失败的卡片能被一眼扫出来（不用逐条读小字）。
2. 两个卡片网格首次加载时，展示与真实卡片同构的骨架屏占位，而不是空白矩形转圈；非首次的重新查询/翻页仍保持现有 `v-loading` 遮罩行为不变。
3. 两个文件 `<style>` 块里所有直接引用 `el-color-*-light-9` / `el-fill-color-*` / `el-text-color-*` 的地方，替换为等价的 `--osr-*` 令牌，为将来定义深色令牌值扫清障碍；不引入任何新的硬编码色值（hex/rgba 字面量）。

## 2. 设计

### 2.1 失败卡片视觉强化（`ptDownloadRecord/index.vue`）

**现状代码**（第 73-76、263-273 行）：卡片本身（`.record-card`）不区分失败态，`.record-fail` 只是卡片内部一条 `padding: 8px 10px` 的提示条。

**改动**：

- 模板：`.record-card` 增加条件 class，失败时叠加 `record-card--failed`：
  ```html
  <div class="record-card" :class="{ 'record-card--failed': item.state === 'FAILED' }">
  ```
- 样式：`.record-card--failed` 只加**左侧色条**，不整卡染色——网格里同时展示多张卡片时，整卡铺红背景观感过重（容易显得"大量报错"），左侧 3px 色条足够让眼睛在扫视网格时被吸引到失败卡片，同时不干扰卡片内其余信息（体积/做种/索引器等）的正常配色：
  ```scss
  .record-card--failed {
    border-left: 3px solid var(--osr-danger);
    padding-left: 13px; // 16px 原 padding 减掉左边框宽度，避免内容整体右移
  }
  ```
- `.record-fail` 提示条本身增强层级感：字重加粗、图标放大到 16px 并纵向居中（原来 `align-items: flex-start` 是为可能换行的长文案设计的，图标放大后改 `center` 更协调），背景/文字色从 `el-color-danger-light-9`/`el-color-danger` 换成 `--osr-danger-light`/`--osr-danger`（`tokens.scss` 已有这两个令牌，属于 2.3 节的令牌替换范围）：
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

`ptSubscription/index.vue` 没有失败态卡片（订阅状态只有 订阅中/已完成/已暂停，靠 `el-tag` 区分，本身已经够醒目），本节改动不涉及该文件。

### 2.2 骨架屏（两个卡片网格）

**判断依据**：`usePtDownloadRecord`、`usePtSubscription`（内部复用 `useTaskList`）的 `loading`/`taskList` 是同一套写法——`loading` 初始为 `true`，`getList()` 前置 `loading.value = true`、`finally` 里置回 `false`，两个页面完全一致，因此可以用统一的判断条件，不需要改 composable。

**方案**：用 `taskList.length === 0` 区分"首次加载（网格里还没有任何卡片形状可看）"和"已有数据、正在重新查询（网格里已经有卡片，只是数据可能变化）"：

- `loading && taskList.length === 0` → 渲染骨架屏网格（固定渲染 6 个占位卡片，撑满 `auto-fill` 网格的前两行左右，具体列数仍由容器宽度决定，不需要精确计算）。
- 其余情况（`taskList.length > 0`，含加载中和加载完成）→ 渲染真实卡片网格，`v-loading="loading"` 保持原样不变——这是"重新查询/翻页"场景，现有的半透明遮罩+转圈图标叠在已有内容上不会有布局跳动，没有必要用骨架屏替换。
- `el-empty` 的显示条件不变（`!loading && taskList.length === 0`）。

用伪代码表示（以 `ptDownloadRecord` 为例，`ptSubscription` 同构）：
```html
<div class="card-grid" v-if="loading && taskList.length === 0">
  <div v-for="n in 6" :key="n" class="record-card-skeleton">
    <el-skeleton animated>
      <template #template>
        <!-- 与 .record-card 内部结构同构的占位块：标题条 + 2-3 行文字条 + 进度条形状 -->
      </template>
    </el-skeleton>
  </div>
</div>
<div class="card-grid" v-else v-loading="loading">
  <!-- 现有真实卡片渲染逻辑不变 -->
</div>
```

**为什么用 `el-skeleton` 而不是手写 shimmer div**：项目里目前没有任何自制的 loading/骨架组件，全部 loading 相关 UI（`el-loading` 指令、`el-empty`、`el-progress`）都直接用 Element Plus 自带组件；`element-plus@2.6.3`（`package.json` 已声明的版本）自带 `el-skeleton`，配合项目已有的 `unplugin-vue-components` 自动导入约定，用 `<el-skeleton>` 不需要新增任何 import 或依赖，是复用现有技术栈成本最低的方案，也避免了自己实现渐变动画、维护一套新的"shimmer"CSS。

`el-skeleton` 的渐变色由它自身的 `--el-skeleton-color`/`--el-skeleton-to-color` 两个 CSS 变量控制；在 `.record-card-skeleton`/`.sub-card-skeleton` 包裹层上覆盖这两个变量，取值指向 `--osr-border-light`/`--osr-bg-page`（现有令牌，不是新硬编码色），保证骨架屏底色和卡片本身的边框/背景色系一致：
```scss
.record-card-skeleton, .sub-card-skeleton {
  --el-skeleton-color: var(--osr-border-light);
  --el-skeleton-to-color: var(--osr-bg-page);
  padding: 14px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
}
```

两个页面的骨架屏内部占位形状不共享成一个组件：`ptSubscription` 的卡片多了一块 72×108 的海报占位（`el-skeleton__image`），`ptDownloadRecord` 没有，两边结构差异不小，各自在自己的 `<template #template>` 里手写十几行 `el-skeleton__item` 更直接；为两个调用点提炼一个共享骨架组件属于过度抽象（YAGNI，和参考设计里"先做减法、真出现第三个场景再抽公共"的取舍一致）。

### 2.3 令牌替换清单（深色模式前置修复）

逐一列出两个文件 `<style>` 块里直接写 `el-color-*`/`el-fill-color-*`/`el-text-color-*`（Element Plus 原生变量，未来定义 `--osr-*` 深色值时不会跟着变）的位置：

| 文件 | 位置 | 现状 | 问题 | 替换为 |
|---|---|---|---|---|
| `ptDownloadRecord/index.vue` | `.record-fail`（第 269-270 行） | `background: var(--el-color-danger-light-9); color: var(--el-color-danger);` | 绕开了 `--osr-*` 体系，且已在 2.1 节里一并重做 | `background: var(--osr-danger-light); color: var(--osr-danger);`（随 2.1 节改动一起落地） |
| `ptSubscription/index.vue` | `.picked-bar`（第 592 行） | `background: var(--el-fill-color-light);` | 同上；`tokens.scss` 已有语义等价令牌 | `background: var(--osr-bg-page);` |
| `ptSubscription/index.vue` | `.sub-year`（第 584 行） | `color: var(--el-text-color-secondary);` | 同上，且和同页 `.sub-meta`/`.record-sub` 等处已经在用的 `--osr-text-secondary` 不一致，属于风格不统一 | `color: var(--osr-text-secondary);` |
| `ptSubscription/index.vue` | `.all-done`（第 602 行） | `color: var(--el-color-success);` | 这是"基色"而非"light-9 淡色底"，本身不是对比度隐患（深色背景上一样能读清楚饱和绿色），但为了令牌体系一致性顺手替换 | `color: var(--osr-success);` |

**不在本次清单里、但顺带确认过没问题的地方**：`.sub-poster`（`background: var(--osr-bg-page)`）、`.sub-poster-placeholder`（`color: var(--osr-text-disabled)`）、各 `.label`/`.value`（`--osr-text-secondary`/`--osr-text-primary`）已经在用 `--osr-*` 令牌，不需要改。`el-tag`（`type="success/warning/danger/info"`）、`el-progress`、`el-empty` 的配色来自 Element Plus 组件自身默认主题，不是这两个文件 `<style>` 块里写的，超出"审查这两个文件 `<style>` 块"的范围，不在本次改动内。

**这次改动之后深色模式的状态**：这两个文件的颜色引用会 100% 落在 `--osr-*` 令牌上。但因为 `tokens.scss` 还没有任何深色取值（1.2 节已说明为什么本次不做），效果上等于"两个文件的颜色描述换了个更规范的写法，视觉表现（浅色下）不变，深色下的表现取决于未来 `tokens.scss` 深色分支怎么定义"——这正是"前置修复"的含义：现在做的是让未来的深色适配工作只需要改 `tokens.scss` 一处，不需要再回头找这两个文件里散落的 `el-color-*`。

## 3. 数据模型改动

无。

## 4. 组件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `openlist-web/src/views/openlist/ptDownloadRecord/index.vue` | 改动 | 模板：`.record-card` 增加 `record-card--failed` 条件 class；`card-grid` 拆成骨架屏分支（`loading && taskList.length===0`）+ 现有真实网格分支（`v-else v-loading`）。样式：新增 `.record-card--failed`、`.record-card-skeleton`；重写 `.record-fail`（令牌替换 + 视觉增强） |
| `openlist-web/src/views/openlist/ptSubscription/index.vue` | 改动 | 模板：`card-grid` 同样拆成骨架屏分支 + 现有真实网格分支。样式：新增 `.sub-card-skeleton`；`.picked-bar`/`.sub-year`/`.all-done` 三处令牌替换 |

不改动：`tokens.scss`、`index.scss`、任何 composable（`usePtSubscription.ts`/`usePtDownloadRecord.ts`/`useTaskList.ts`）、`views-mobile/ptSubscription`、`views-mobile/ptDownloadRecord`（移动端页面用的是完全独立的模板/样式，本次任务描述只点名了 PC 端两个文件，移动端不在范围内，见第 8 节）、任何后端文件、任何数据库脚本。

## 5. API

无新增/变更接口。

## 6. 前端改动

已在第 2 节详细说明，此处不重复。补充两点跨文件的一致性要求：

- 两个页面骨架屏的占位卡片数量统一取 6，不做成可配置项——纯视觉占位，不需要精确匹配 `pageSize`（首次加载时甚至还不知道会返回多少条）。
- 骨架屏分支和真实网格分支使用同一个 `.card-grid` 类名（保证 `grid-template-columns`/`gap` 完全一致，两个分支切换时不会有布局跳动），只是内部子元素不同。

## 7. 测试计划

纯样式改动，没有可断言的业务逻辑分支，不新增/修改单元测试。验证方式为手动视觉走查（对齐项目里其它纯样式改动的验收方式，如近期的 `SidebarMenuItem`/菜单分类相关提交）：

- **失败卡片**：`ptDownloadRecord` 页面在有 `FAILED` 状态记录时，左侧色条和加强后的提示条在网格里能一眼扫出来；非失败卡片外观不受影响（`padding-left` 补偿没有导致内容错位）。
- **骨架屏**：
  - 首次进入 `ptSubscription`/`ptDownloadRecord` 页面（或强刷新），网格短暂展示骨架卡片形状而非空白转圈，数据回来后骨架屏消失、真实卡片渲染，无布局跳动。
  - 已有数据后点分页/改搜索条件重新查询，网格保持现有 `v-loading` 半透明遮罩行为（不应该退化成再次出现骨架屏——这是判断条件 `taskList.length===0` 要覆盖的分支）。
  - 搜索结果为 0 条时，`el-empty` 正常展示（不会卡在骨架屏或被骨架屏和空状态同时渲染）。
- **令牌替换**：改动前后浏览器渲染截图对比（当前唯一存在的主题下），确认 `.record-fail`/`.picked-bar`/`.sub-year`/`.all-done` 四处视觉效果与改动前一致（因为 `--osr-*` 令牌当前取值和原来的 `el-color-*`/`el-fill-color-*`/`el-text-color-*` 在浅色主题下渲染结果本就应当相同，这一步只是确认替换没有引入取值偏差）。
- 涉及模板结构变化（`v-if`/`v-else` 分支），跑一遍 `npm run build`（含 `vue-tsc` 类型检查）确认没有模板/类型错误。

## 8. 不做的事情（本次范围之外）

- 不新增深色模式：不定义 `tokens.scss` 的深色取值，不引入 `prefers-color-scheme` 媒体查询或手动切换 store/toggle。这是本次"令牌替换"工作之后自然的下一步，但涉及全站配色（导航栏、侧边栏、表格、弹窗等），需要独立的设计文档和取舍讨论（比如"跟随系统"还是"用户手动切换 + 记忆偏好"），不塞进这次的"两个页面样式打磨"里。
- 不处理 `el-tag`/`el-progress`/`el-empty` 等 Element Plus 组件自身的默认配色（这些不是这两个文件 `<style>` 块里写的，属于全局 `index.scss` 或 Element Plus 主题层面的事）。
- 不改动 `views-mobile/ptSubscription`、`views-mobile/ptDownloadRecord`（移动端页面模板/样式与 PC 端完全独立维护，本次任务未点名，且移动端列表通常用的是 `MobilePager`/卡片流而非本次讨论的 `card-grid` 网格，改动方式不能照抄）。
- 不引入骨架屏共享组件：两个页面各自在自己的 `<style>`/`<template>` 里写占位结构，不为只有两个调用点的场景提前抽象。
- 不改变失败重试的交互逻辑（`handleRetry`/`retryingIds` 不变），只强化视觉呈现。
