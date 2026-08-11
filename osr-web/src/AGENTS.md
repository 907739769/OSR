# OpenList-web 前端知识库

## OVERVIEW
Vue 3 + Vite + Vuetify 3 + Pinia 前端应用，支持 PWA。包含 PC 端 (`views/`) 和移动端 (`views-mobile/`) 两套界面。

> 本文件是前端唯一的 AI 知识库，Claude Code 与 opencode 共用。同目录 `CLAUDE.md` 仅做引用，改动请直接改本文件。后端及全局约定见项目根目录 `AGENTS.md`。

## STRUCTURE
```
src/
├── api/                    # API 请求层
│   ├── auth.ts             # 认证 API
│   ├── request.ts          # axios 封装 (拦截器、token 处理)
│   ├── monitor/            # 监控相关 API (定时任务)
│   ├── openlist/           # 业务 API (见下)
│   └── system/             # 系统管理 API
├── components/             # 公共组件 (DirectoryTreeSelect, ChangePasswordDialog, PageHeader, StatusChip, ThemeSwitch, MiniTrend, mobile/*)
├── composables/            # 组合式函数 (useTaskList, useRecordList, useDebounce, useThemeMode, useMenuLinks 等)
├── layouts/                # 布局组件 (DesktopLayout, MobileLayout)
├── router/                 # 路由配置 (动态路由)
│   └── index.ts
├── stores/                 # Pinia 状态管理
│   ├── app.ts              # 应用全局状态 (设备检测、侧边栏)
│   ├── permission.ts       # 权限/菜单
│   └── user.ts             # 用户状态
├── styles/                 # 全局样式 (tokens.scss 设计令牌, list.scss PC 列表公共, mobile-list.scss 移动端列表公共)
├── types/                  # TypeScript 类型定义 (SearchParams, PageResult)
├── views/                  # PC 端页面 (openlist/, system/, monitor/, dashboard/)
├── views-mobile/           # 移动端页面 (对应 PC 端)
├── App.vue
└── main.ts
```

`api/openlist/` 模块：
`copyTask.ts copyRecord.ts dashboard.ts hitokoto.ts path.ts ptDownloader.ts ptDownloadRecord.ts ptFilterConfig.ts ptIndexer.ts ptMediaServer.ts ptSubscription.ts renameConfig.ts renameDetail.ts renameOrphan.ts renameTask.ts strmRecord.ts strmTask.ts`

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| 页面组件 | `views/` + `views-mobile/` | 按模块分目录 (openlist/, system/, monitor/, dashboard/) |
| API 调用 | `src/api/openlist/` | 17 个业务 API 模块 |
| 列表逻辑 | `src/composables/` | useTaskList, useRecordList 等通用逻辑 |
| 路由 | `src/router/index.ts` | 动态路由加载 |
| 状态管理 | `src/stores/` | Pinia store (app/permission/user) |
| 布局 | `src/layouts/` | DesktopLayout / MobileLayout |
| 移动端组件 | `src/components/mobile/` | MobileSearchPanel, MobilePager, FullTextDialog |
| PWA 配置 | `vite.config.ts` | VitePWA 插件配置 |

## CONVENTIONS
- **自动导入**: vite-plugin-vuetify (autoImport) + unplugin-auto-import + unplugin-vue-components，`vue`/`vue-router`/`pinia` 与 `v-*` 组件无需手动 import
- **`@` 别名**: 指向 `src/` 目录
- **API 层**: 返回标准 `{ code, msg, data }` 格式，axios 拦截器自动处理
- **路由**: 后端动态返回菜单，前端根据权限生成路由
- **列表页模式**: 使用 composables (`useTaskList`/`useRecordList`) 封装增删改查 + 分页 + 搜索
- **移动端**: `views-mobile/` 独立于 `views/`，使用 `MobileSearchPanel` + `MobilePager` + `FullTextDialog` 组件
- **移动端页面**: 弹窗统一 `width="92%"`（不要再写 85%/90%/94%，也不要用 `max-width` 传百分比）
- **TypeScript**: 严格模式，`vue-tsc` 类型检查
- **CSS 变量 / 设计令牌**: 见下方「DESIGN SYSTEM」一节
- **暗色模式**: 顶栏 ThemeSwitch 切换 浅色/深色/跟随系统，`useThemeMode`（模块级单例）同步 Vuetify 主题 (osrLight/osrDark) 与 `<html data-theme>`，localStorage key `osr-theme` 持久化；切换时派发 `osr-theme-change` 事件供 ECharts 等 canvas 场景重绘（`osrCssVar()` 读取当前令牌值）
- **列表页公共样式**: PC 用 `styles/list.scss`，移动端用 `styles/mobile-list.scss`，**禁止在页面里复制这些类**；各页只保留特有子规则（见下方 DESIGN SYSTEM）
- **PageHeader**: 每个业务页顶部都要有 `PageHeader`（图标+标题+描述+操作区），不要自造 page-header 样式
- **页面切换不要包 `<transition>`**: 两个 Layout 里都刻意去掉了。在「`<KeepAlive>` 与裸 `<component>` 交替 + 页面组件异步加载」这个结构下，过渡类不会被清掉、离场过渡收不到结束事件，结果是每导航一次旧页面就留在新页面下方越堆越多（`mode="out-in"` / `:duration` 都压不住）。要重做切换动画需先解决异步组件的过渡时机
- **Dashboard**: PC 统计卡用 `MiniTrend`（SVG sparkline，颜色走 `--osr-*` CSS 变量自动适配暗色）；快捷入口统一用 `useMenuLinks`（菜单树拍平，PC/移动共用，禁止写死路径）；PT 概览/失败列表/图表空态/骨架屏均在 `views/dashboard/desktop.vue` 内

## DESIGN SYSTEM

从 Element Plus 迁到 Vuetify 后做过一轮收口，下面是收口后的单一事实来源。
`src/styles/__tests__/design-system.spec.ts` 与 `src/router/__tests__/device-parity.spec.ts`
会在 CI 里挡住违反这些约定的改动，改之前先看一眼这两个 spec。

### 颜色只有一个来源
- **品牌色/表面色的唯一定义在 `plugins/vuetify.ts` 的 `osrLight` / `osrDark`。**
  `styles/tokens.scss` 里的 `--osr-primary` / `--osr-surface` / `--osr-success` … 都是
  `rgb(var(--v-theme-*))` 派生，不再抄第二份色值，暗色切换由 Vuetify 单点负责。
- `tokens.scss` 的 `:root[data-theme='dark']` 块**只剩文字 / 边框 / 阴影**——这些 Vuetify 没有对应项。
- primary 的层级用语义名，不要再用 Element Plus 那套 `light-1..9` 阶梯：
  `--osr-primary-subtle`（选中块背景）/ `--osr-primary-muted`（浅描边）/
  `--osr-primary-accent`（强调描边）/ `--osr-primary-hover`（hover 文字）
- **样式里不写死十六进制颜色**。语义色一律 `rgb(var(--v-theme-xxx))` 或 `--osr-*`。
  例外只有：海报占位装饰渐变、日志终端配色、登录页品牌渐变、ECharts 配色（spec 里有白名单）。

### 布局类单源
| 类 | 定义在 | 用途 |
|---|---|---|
| `.page-container` `.search-card` `.table-card` `.action-bar` `.batch-toolbar` `.pagination-wrapper` | `styles/list.scss` | PC 列表页骨架 |
| `.search-fields` + `.field-sm/.field-md/.field-lg/.date-field` | `styles/list.scss` | PC 搜索区（**只有这一种搜索布局**，不要再用 `v-row/v-col`、自造的 `.search-row`/`.search-form-row`） |
| `.inline-fields` | `styles/list.scss` | 弹窗里的一行输入组合（宽度自定，不套搜索区档位） |
| `.log-search-form` | `views/monitor/job/index.vue` | 日志弹窗内嵌搜索行（布局已复用 `.inline-fields`，此私有类只留分隔线与 select 宽度） |
| `.path-box/.path-row/.path-label--src\|dst\|mon/.path-text/.path-name` | `styles/list.scss` | 表格里的「源/目标/监控」路径对照 |
| `.card-grid` `.item-card`（`--failed/--selectable/--compact`）+ `.card-header/body/row/footer` | `styles/list.scss` | PC 卡片网格（PT 配置类页面） |
| `.mobile-page` `.task-list` `.task-card` `.fab-add` `.batch-bar` `.card-actions` `.drawer-actions` `.date-range-fields` | `styles/mobile-list.scss` | 移动端页面骨架与卡片 |
| `.mobile-card*` | `styles/mobile-list.scss` | PC 页在 <768px 时的表格降级卡片（monitor/job、dict/*） |

**移动端卡片解剖结构固定为**（不要再自造 `.record-card` / `.sub-card` / `.file-name-row` 这类同义名）：
```
<v-card class="task-card">        surface/圆角/阴影由 v-card 给
  .card-checkbox
  .card-content                   flex column，gap 6px 统一纵向节奏
    .card-top > .card-title-row > .card-title-icon + .card-title[.card-title--link]
    .card-path[.card-path--link|--success|--warning] > .card-path-icon + .card-path-text
    .card-detail > .detail-row > .label + .value
    .card-time
    .card-actions > … + .action-more
```

### 卡片网格页的分页尺寸
`.card-grid` 是 `repeat(auto-fill, minmax(300px, 1fr))`，列数随窗口宽度在 1~8 之间变化，
**任何写死的每页条数都会在某个宽度上让最后一行只填一半**——用户把「没填满」读成「没有下一页」。
PC 端一律用 `composables/useGridPageSize.ts`：把 `gridRef` 绑到 `.card-grid`（`v-if`/`v-else`
两个分支都要绑，骨架屏阶段就得量得到），它数 `grid-template-columns` 的轨道数得到真实列数，
每页条数取 `max(3 行, 12 条)` 并向上取整到整行；分页器 `:items` 用它给的 `pageSizeOptions`
（基准的 1/2/4 倍），换档存的是**倍数**不是绝对条数，窗口变宽后档位跟着换算，
不会出现「select 的值不在 items 里」的空白。业务 composable 传 `autoLoad: false`
（`ListLoadOptions`），首次加载由 `useGridPageSize` 挂载后触发，避免先按兜底值发一次
再按真实列数重发。移动端是单列，保持 `defaultQuery.pageSize: 12` 即可。

数轨道数对 `auto-fill` 和 `card-grid--wide` 的 `auto-fit` 都成立：实测 Chrome 对 auto-fit
会把折叠掉的空轨道以 `0px` 报出来（3 张卡 / 6 列时是 `790px 790px 790px 0px 0px 0px`），
所以卡片数少于列数时也数得对。拿到的还是没展开的 `repeat()/minmax()`（jsdom、元素未渲染）
时保持兜底值，不瞎猜。

已接入：ptDownloadRecord / ptSubscription / ptIndexer / ptDownloader / ptMediaServer /
ptTorrentBlacklist / wecomUser（即全部 `.card-grid` 页面）。新增卡片网格页照抄即可。

### 组件
- **`FormField`**：外置 label + 控件 + 下方说明。用于控件自身没有 label 的场景
  （DirectoryTreeSelect / v-radio-group / v-switch / 输入框+按钮组合），或需要补说明文字时。
  `v-text-field` / `v-select` / `v-textarea` 若不需要说明，**直接用它们自己的 `label` prop**，
  别套 FormField —— 否则同一个弹窗里会出现浮动 label 和贴顶 label 两种标签位置。
  **禁止再手写 `.form-item` / `.form-label` / `.field-label` / `.rule-field-label`**（那是 `el-form-item` 的复刻）。
- **`StatusChip`**：所有状态徽章走它。二元开关用 `<StatusChip :value="row.enabled" />`，
  开=success 关=error 全站一致；自定义状态用 `<StatusChip type="warning" text="下载中" />`。
- **不作为表单字段的勾选框一律用 `v-checkbox-btn`，不要用 `v-checkbox`**。
  后者是**表单字段**：内部套一层 `VInput`，带来 min-height、label 的 `opacity: .6`、
  以及 details/hint 行的预留空间。把它放进批量工具条、卡片角标、全选行这类紧凑位置，
  就得写 `.v-selection-control { min-height: auto }` + `.v-label { opacity: 1 }` 去压——
  **需要写覆盖样式本身就是选错组件的信号**。`v-checkbox-btn` 直接渲染 `VSelectionControl`，
  没有那层外壳（`v-data-table` 表头的全选用的就是它），`label` / `indeterminate` /
  `density` 一样都不少，且没有 `hide-details`（那是 `VInput` 的 prop，不需要）。
- **`PageHeader`**：每个业务页顶部都要有。
- **「全选」是操作栏里的一个文字按钮，不是勾选框，也不单独占一行**。
  位置固定在**「取消」的正前面**（PC 的 `.batch-toolbar`、移动端的 `.batch-bar`；
  卡片网格页没有「取消」时放在 `.action-left` 末尾），样式与同一条栏里的「取消」完全一致
  （`variant="text"`，尺寸跟随该栏——批量条 `size="small"`，卡片网格页的操作栏用默认尺寸），
  统一挂 `.batch-select-all-btn`。文案随 `isAllPageSelected` 在「全选」/「取消全选」之间切换。
  走过两版弯路：先是 `v-checkbox`（表单字段，得写一堆覆盖样式压 `VInput` 外壳），
  再是列表上方单独一行 `v-card` 包着的勾选框——两版都因为「与周围操作按钮不是一种东西」而显得突兀。
  结论是**批量操作区里只有按钮**，不要混进表单控件。
- 卡片一律用 `<v-card>`，不要手写 `background: var(--osr-surface) + border-radius + box-shadow`。

### 弹窗
- PC 三档：`max-width="480"`（确认类）/ `600`（表单类）/ `900`（数据表类）
- 移动端统一 `width="92%"`
- 次要按钮（取消/关闭/测试连接）统一 `variant="outlined"`，主按钮 `variant="flat"`

### PC / 移动端对齐
- 新增功能必须同时改 `views/` 和 `views-mobile/`。
- 两端功能差异由 `device-parity.spec.ts` 比对 composable 解构出的动作集合；
  确有差异要在该 spec 的 `ALLOWED_GAPS` 里登记原因（登记本身就是一次评审）。
- 选择/分页这层交互外壳按设备不同是正常的，已在 spec 的 `SHELL_ONLY` 里排除。

## ANTI-PATTERNS
- 不要在组件中直接调用 `axios`，统一用 `src/api/` 中的封装
- 不要绕过路由守卫，权限校验在 store 中统一处理
- 不要在组件中写大量业务逻辑，抽到 composables/
- 移动端页面不要使用 PC 端组件 (Vuetify PC 组件)
- 每个页面都要考虑H5端的适配
- 列表页不要各自实现分页/搜索逻辑，复用 `useTaskList`/`useRecordList`
- **勾选逻辑只有一份：`composables/usePageSelection.ts`**，`useTaskList` / `useRecordList` 都内置了它，
  业务 composable 不要再自己写 `toggleSelect` / `handleCardClick` / `clearSelection` / 全选本页。
  曾经这四个函数在 7 个业务 composable 里各抄一份，其中三份还顺手手动同步 `single`/`multiple`
  两个 ref——漏改一处就是「明明选中了，修改按钮还是灰的」。现在 `single`/`multiple` 由
  `selectedIds` 派生（computed），没有可漏改的同步点。选择集**跨页累加**，全选/半选只判当前页，
  取消全选也只摘当前页那批。
- **批量操作里「能勾选的范围」与「动作生效的范围」是两件事，不要用「不给勾选框」来表达后者**。
  PT 下载记录踩过：批量重试只对 FAILED 成立，于是勾选框只渲染在 FAILED 卡片上——用户点开
  「批量操作」看到一页全是没有勾选框的卡片，只会读成「批量操作坏了」。正确做法是勾选放开到
  全部记录（拉黑这类动作对任意状态都成立），受限的那个动作自己把范围收回来，
  并在按钮上标出生效条数（`retryableSelectedIds`）。
- **搜索区的「重置」不要靠 `queryRef.value?.reset?.()`**。Vuetify 的 `v-form.reset()` 是把注册在
  表单里的输入框置为 `null`，不是还原默认值——`defaultQuery` 里的非空默认值（订阅页 `status: 'ACTIVE'`、
  孤儿页 `status: '0'`）会被清成"全部"，没渲染成表单控件的条件（路由带进来的 `subId`、日期区间写出的
  `params`）它也管不到；页面漏写 `ref="queryRef"` 时可选链直接吃掉调用，重置静默失效。
  统一走 `useTaskList`/`useRecordList` 的 `resetQuery`，它用 `resetQueryParams()`
  （`composables/queryParams.ts`）按默认值快照还原。新增查询条件请写进 `defaultQuery`，
  不要直接往 `queryParams` 上挂字段。
- **不要写死路由 path**。后端菜单 path 历史上有 `/openlist/xxx` 与 `/openliststrm/xxx` 两种前缀，
  写死会跳 404。用 `getRoutePathForComponent('openlist/xxx/index')` 按 `meta.componentKey` 反查
  （不要用组件对象引用比对，HMR 下会失效）。菜单快捷入口用 `useMenuLinks`。
