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
├── components/             # 公共组件 (SearchPanel, PageHeader, DirectoryTreeSelect, ChangePasswordDialog,
│                           #           StatusChip, ThemeSwitch, MiniTrend, AnimatedNumber, mobile/*)
│   └── dialogs/            # ★ PC 与移动端<共用>的表单弹窗 (FormDialogShell + 各页 XxxFormDialog)
├── composables/            # 组合式函数 (useTaskList, useRecordList, useDataTable, useSearchPanel, useSidebarGroups, useBreadcrumb, useCurrentUser, useThemeMode, usePageTransition, useMenuLinks, useActionSheet, useMobileTabs 等)
├── layouts/                # 布局组件 (DesktopLayout, MobileLayout)
├── router/                 # 路由配置 (动态路由)
│   └── index.ts
├── stores/                 # Pinia 状态管理
│   ├── app.ts              # 应用全局状态 (设备检测、侧边栏)
│   ├── permission.ts       # 权限/菜单
│   └── user.ts             # 用户状态
├── styles/                 # 全局样式 (tokens.scss 设计令牌, motion.scss 动画库, surface.scss 深度/玻璃层,
│                           #           list.scss PC 列表公共, mobile-list.scss 移动端列表公共, menu.scss 侧边菜单)
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
| 两端共用的表单弹窗 | `src/components/dialogs/` | `FormDialogShell` + 10 个 `XxxFormDialog`，见下方「表单弹窗两端共用」 |
| 移动端组件 | `src/components/mobile/` | MobileListPage(外壳), MobileSearchPanel, MobileBatchBar, MobileActionSheet, MobilePager, FullTextDialog, MobileTabSettingsDialog |
| 移动端外壳/导航 | `src/layouts/MobileLayout.vue` + `composables/useMobileTabs.ts` | 顶栏 / 抽屉 / 底部 tab，见下方「移动端外壳」 |
| PWA 配置 | `vite.config.ts` | VitePWA 插件配置 |
| 动效 / 深度 / 排版令牌 | `src/styles/tokens.scss` + `motion.scss` + `surface.scss` | 见下方「动效系统」「深度系统」「排版」 |
| 图表配色 | `src/plugins/echartsTheme.ts` | `chartBase()` / `lineSeries()` / `barSeries()` / `chartEmptyOption()` |

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
- **暗色模式**: 顶栏 ThemeSwitch 切换 浅色/深色/跟随系统，`useThemeMode`（模块级单例）同步 Vuetify 主题 (osrLight/osrDark) 与 `<html data-theme>`，localStorage key `osr-theme` 持久化；切换时派发 `osr-theme-change` 事件供 ECharts 等 canvas 场景重绘（`osrCssVar()` 读取当前令牌值）；切换带**从点击位置扩开的圆形揭示**（View Transitions，见下方「动效系统」），调用方要把点击事件传给 `setMode(mode, event)`
- **列表页公共样式**: PC 用 `styles/list.scss`，移动端用 `styles/mobile-list.scss`，**禁止在页面里复制这些类**；各页只保留特有子规则（见下方 DESIGN SYSTEM）
- **PageHeader**: 每个业务页顶部都要有 `PageHeader`（图标+标题+描述+操作区），不要自造 page-header 样式
- **页面切换动画走 `usePageTransition`（WAAPI，只做入场不做离场），仍然不要包 `<transition>`**: 两个 Layout 里都刻意没有 `<transition>`——在「`<KeepAlive>` 与裸 `<component>` 交替 + 页面组件异步加载」这个结构下，过渡类不会被清掉、离场过渡收不到结束事件，每导航一次旧页面就留在新页面下方越堆越多（`mode="out-in"` / `:duration` 都压不住）。现在的做法见下方「动效系统」一节
- **Dashboard**: PC 统计卡用 `MiniTrend`（SVG sparkline，颜色走 `--osr-*` CSS 变量自动适配暗色，折线带 `pathLength="1"` 的描边动画）；统计数字一律套 `AnimatedNumber`（rAF 滚动，自己解析 `85%` / `--` / `12 分钟` 这类混合形态）；快捷入口统一用 `useMenuLinks`（菜单树拍平，PC/移动共用，禁止写死路径）；PT 概览/失败列表/图表空态/骨架屏均在 `views/dashboard/desktop.vue` 内

## DESIGN SYSTEM

从 Element Plus 迁到 Vuetify 后做过一轮收口，下面是收口后的单一事实来源。
`src/styles/__tests__/design-system.spec.ts` 与 `src/router/__tests__/device-parity.spec.ts`
会在 CI 里挡住违反这些约定的改动，改之前先看一眼这两个 spec。

### 动效系统

改造前全库 **0 个 `@keyframes`**、31 处 `transition` 全用 CSS 默认的 `ease`，
没有 `prefers-reduced-motion`、没有 `cubic-bezier`。现在分三层，改之前先读完这节。

**1. 令牌层（`tokens.scss`）：时长与曲线拆开。**
旧的 `--osr-transition-fast/base` 把两者焊死在一个变量里，「快时长 + 弹性曲线」
这类组合根本表达不出来。现在是 `--osr-dur-1..4` × `--osr-ease-out|in-out|spring`，
旧的两个保留为别名（全站 31 处引用不必改写，换掉底层曲线后自动跟着升级）。
**曲线只有三条，不要再加**：`ease-out`（expo-out，绝大多数场景）、`ease-in-out`
（两端都在视口内的位移）、`ease-spring`（**只**用于确认类正反馈，用多了显廉价）。

**`prefers-reduced-motion` 的降级做在令牌层**——把 4 个时长压到 `0.01ms`，
新增动画自动受管。压时长而不是 `animation: none`：后者会让依赖 `animationend` /
`transitionend` 收尾的逻辑永远收不到事件（`index.html` 的启动屏就是靠
`transitionend` 摘节点的）。**因此动画里的时长一律写 `var(--osr-dur-*)`，不要写死 ms**，
写死就绕过了这层。真正需要写死秒数的持续型装饰动画（登录页的极光漂移、logo 呼吸）
要自己补一条 `@media (prefers-reduced-motion: reduce) { animation: none }`。

**2. 动画库（`motion.scss`）：全站唯一的 `@keyframes` 定义处。**
入场 `osr-fade-up/fade-in/scale-in`、持续态 `osr-shimmer/pulse-dot/pulse-ring/sweep`、
SVG 描边 `osr-draw-line`。**动画只做 transform / opacity / filter / clip-path**
（唯一例外是 `stroke-dashoffset`，它本身走合成层）——动 width/height/box-shadow
会触发布局或重绘，在这个动辄几百行的列表系统里一次入场就是几百次重排。

错位入场用 `.osr-enter` + 每个子项 `:style="{ '--osr-i': index }"`。
步长 40ms 是量出来的（低于 30ms 读成「一起出现」，高于 60ms 时一屏 12 张卡
最后一张要等 720ms）；**延迟用 `min(var(--osr-i), 8)` 封顶**，不封顶的话
100 项列表的最后一项要等 4 秒，那已经不是动效是故障。
`--osr-i` 的默认值 0 登记在 `tokens.scss`（`design-system.spec.ts` 会校验
全站 `var(--osr-*)` 都有定义，这条契约不靠测试白名单绕过）。

**3. 页面转场：`composables/usePageTransition.ts`，只做入场、不做离场。**
没有离场就没有「等旧元素动画结束再移除」这件事，上面那个死结的成因整个不存在。
用 `element.animate()`（WAAPI）而不是 CSS class：**播完自动回到原样式，
不留任何需要清理的类名或内联样式**——这正是上一版 CSS 过渡出问题的地方；
它还天然兼容 KeepAlive（动画挂在**容器**上，与里面的组件是否从缓存恢复无关，
挂在页面组件根节点上的话 keep-alive 命中时不重新挂载、动画根本不重放）。
时机是 `router.afterEach` + 一个 `nextTick`：vue-router 在导航过程中已经
await 过异步组件的 import，此时 chunk 已到位。

**刻意没有给导航用 View Transitions**，尽管它看起来正好能绕开那个死结：
`startViewTransition` 抓「新」快照的时机比上面早，本项目页面组件是异步加载的，
首次进入某页时那一刻 chunk 还没到、router-view 还是空的，转场会把**空白**
当成新页面淡进来。

**4. 主题切换的圆形揭示（`useThemeMode`）用的才是 View Transitions。**
它不涉及导航，回调里 `await nextTick()` 就能保证新快照完整；而「同一时刻同时
呈现新旧两套主题」除了截图没有别的做法。三个前提任一不满足就退回瞬间切换：
浏览器不支持、用户开了减少动效、**拿不到点击坐标**（键盘触发的 click 也算——
浏览器照样派发 MouseEvent 但 `clientX/Y` 全是 0，判据用 `detail === 0`）。
`motion.scss` 里 `html.osr-theme-transition` 那段负责关掉浏览器默认的 cross-fade
并把旧快照钉在下层**保持不透明**——旧的一层一淡出就会透出底色，圆环外围会先白一下。

**5. 进行态要看得出来**：`StatusChip` 的 `pulse` 属性给文案前加一个呼吸圆点，
**只给真正还在推进的状态**（下载中 / 处理中 / 上传中），稳态（已推送 / 保种中）不要挂；
进度条加 `.osr-progress--active` 得到流动高光。这个系统里任务动辄跑几十分钟，
「还在跑」和「卡住了」是用户最需要区分的两件事，改造前两者形态完全一样。
它是显式开关而不是按文案自动推断——挂满了就等于没有强调。

### 深度系统（`surface.scss`）

**浅色与暗色的分层手段是两件不同的事，不是同一组阴影调深浅。**
浅色靠投影，三档都带一点 primary 色相（纯黑投影落在暖白底 `#F7F5F1` 上会发灰发脏）；
**暗色下投影物理上看不见**——改造前 3 档暗色阴影只是把黑色 alpha 从 .06 加到 .4，
实际等于没有，暗色界面全靠 1px 边框分层、因此是彻底扁平的。暗色的手段是
**1px 亮环（`--osr-ring`）+ 顶部内高光（`--osr-highlight`）**，投影只作环境遮蔽。

其余令牌：`--osr-glow-*`（辉光，**只给活跃/被强调的元素**，日常表面不挂）、
`--osr-glass-bg` / `--osr-glass-blur`（顶栏/侧边栏/菜单/弹窗的玻璃层，
`saturate` 不能省——只做 blur 会让透上来的内容发灰）、`--osr-ambient`
（铺在 `.v-application::before` 上的环境光，用伪元素是因为 Vuetify 会重写
`.v-application` 的 background、写在上面会被盖掉）。

`surface.scss` 全是**对 Vuetify 内部节点的覆盖**，与 `list.scss` 那种「本项目自己的类」
不是一回事，所以单独一个文件。特异性靠 `.v-application` 前缀 + 后加载顺序，
**不要用 `!important`**——那会连页面自己的 scoped 覆盖一起挡掉。

### 排版

- **等宽字体栈单源在 `--osr-font-mono`**（JetBrains Mono 变量字体，
  `@fontsource-variable` 本地打包、**不引 CDN**：本项目是 Docker 自部署常跑内网，
  外链字体的下场是每次首屏等一次超时）。改造前这个栈在 5 个文件里各写各的
  （`'Courier New'` / `'Consolas','Monaco','Courier New'` / `Consolas,monospace` /
  `monospace` / `'SF Mono','Courier New'`），同一个日志终端在 Mac 与 Windows 上
  落到的字体宽度不同、路径对照会错位。引的是含全部子集的 `index.css`，
  每个 `@font-face` 带 `unicode-range`，中文内容不会触发下载（实测只拉 latin 那 40KB）。
- **`font-variant-numeric: tabular-nums` 开在 `html, body` 上**。这是个满屏是数字的系统
  （体积/集号/耗时/成功率/做种数），比例数字会让计数在 999 → 1000 时左右抖动。
- 工具类 `.osr-mono` / `.osr-numeric`（`index.scss`）用于路径、哈希、日志这类机器产物。
  **PC 的 `.path-text`（目录路径）已挂等宽字体，`.path-name`（文件名）刻意没挂**——
  文件名多半是中文剧名，等宽对 CJK 字形没有实际作用，只会让同一行出现两种数字宽度。
  移动端的 `.card-path-text` 是路径+文件名合并展示的，同理不挂。
- 字号阶梯 `--osr-fs-xs..3xl`。改造前全站散落着 11/12/13/14/15/17/18/20px 六七种字号，
  同一层级的信息常常差 1px——差 1px 比差 3px 更糟，它不构成层级、只构成毛刺。

### ECharts 主题（`plugins/echartsTheme.ts`）

**每次 `setOption` 前调 `chartBase()`，不要把返回值缓存到模块级变量**——
它读的是当前生效的 `--osr-*` 令牌，缓存下来等于把第一次渲染时的主题钉死了。
系列用 `lineSeries()` / `barSeries()`，空态用 `chartEmptyOption()`。

修掉的 bug：改造前 4 个图表页把系列色写成字面量 `'#B4690E'` / `'#3F8F5F'` / `'#C0362C'`，
那是 **osrLight 的色板**。切到暗色时 `osr-theme-change` 触发重绘、坐标轴跟着换了，
**折线颜色却没换**，图表里的琥珀与页面其余部分差着一整档明度，看起来像图表没刷新。

**横向条形图（索引器命中率）要把 `base.xAxis` / `base.yAxis` 互换着取**：
两条轴的角色与折线图相反，直接铺的话虚线网格会画在分类轴那一侧
（每个索引器名字后面拖一条线），而数值轴反倒没有刻度参考。

### 登录页

全站唯一**不跟随明暗主题**的页面：固定的深色放映厅调性（极光 + 网格 + 暗角三层纯 CSS
装饰 + 玻璃面板）。它是一个**时刻**而不是一个工作区。三条：
- 卡片用 `<v-theme-provider theme="osrDark">` 包起来，**不要手写颜色覆盖**——
  里面全是 Vuetify 组件，让它们自己按暗色主题渲染才不会漏掉聚焦态、错误态这些分支。
- 极光层用 `inset: -20%` 溢出容器（免得模糊边缘露出硬边），靠 `.login-stage` 的
  `overflow: hidden` 兜住。漏掉那条不报错，只会让手机上多一条横向滚动条，
  `e2e/mobile.spec.ts` 的登录页用例钉住了这一点。
- `blur` 放在极光**容器**上而不是每个色团上（三个子元素各自 blur 会开三个滤镜层）；
  色值写死并已登记在 `design-system.spec.ts` 的 `ALLOW_LITERAL` 里——
  它们不是语义色，是一幅画的配色。

### 颜色只有一个来源
- **品牌色/表面色的唯一定义在 `plugins/vuetify.ts` 的 `osrLight` / `osrDark`。**
  `styles/tokens.scss` 里的 `--osr-primary` / `--osr-surface` / `--osr-success` … 都是
  `rgb(var(--v-theme-*))` 派生，不再抄第二份色值，暗色切换由 Vuetify 单点负责。
- `tokens.scss` 的 `:root[data-theme='dark']` 块只覆盖**Vuetify 没有对应项**的那些：文字 / 边框 /
  深度（阴影·亮环·高光）/ 辉光 / 玻璃 / 环境光。品牌色与表面色不在此处。
- primary 的层级用语义名，不要再用 Element Plus 那套 `light-1..9` 阶梯：
  `--osr-primary-subtle`（选中块背景）/ `--osr-primary-muted`（浅描边）/
  `--osr-primary-accent`（强调描边）/ `--osr-primary-hover`（hover 文字）
- **改了 `X` 就必须同时改 `on-X`，Vuetify 会拿默认主题的那一份来补缺。** 自定义主题名不叫
  `light`/`dark` 也照样与默认主题 deep merge（`parseThemeOptions` 按 `dark` 布尔值选一份合并），
  所以少写一个 `on-X` 不会报错、不会告警，只是**静默继承上游为上游的 X 配的前景色**。
  事故：`surface-variant` 被改成浅米色 `#EDE7DD` 却没动 `on-surface-variant`，它继承了默认
  light 主题的 `#EEEEEE`——而 `v-tooltip` 的底色与字色**直接就是这一对变量**（`v-snackbar`、
  `v-chip`、`v-slider` 也在用），于是全站 tooltip 都是浅米底配近白字，对比度 1.06:1，完全看不见。
  暗色那份坏得更彻底（深蓝灰底 + 继承来的纯黑字，1.7:1），只是没人在暗色下展开过 tooltip。
  **`surface-variant` 尤其不能当成「浅色的面板变体背景」用**：Vuetify 默认 light 主题里它是
  `#424242`，整套组件都按「这是个深色浮层底」在用它（不透明用法给 tooltip/snackbar，
  `rgba(…, .2~.3)` 的低透明用法给 chip/slider track），改成浅色两类用法会一起坏。
  `plugins/__tests__/theme-contrast.spec.ts` 按 WCAG 2.1 算每对 `X`/`on-X` 的对比度、红线 4.5:1；
  `osrLight` 的 primary(4.23) 与 success(3.96) 是主题建立时就有的既有偏差，已在该 spec 的
  `KNOWN_BELOW_AA` 里登记基线（只钉住不再更差），要真修得动品牌色本身。
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
| `.mobile-page` `.task-list` `.task-card` `.fab-add` `.batch-bar` `.card-actions` `.drawer-actions` `.date-range-fields` | `styles/mobile-list.scss` | 移动端页面骨架与卡片（骨架三件套已被 `MobileListPage` 包起来，页面不再直接写） |
| `.menu-item` `.menu-group-label` | `styles/menu.scss` | 两端侧边菜单项（两个 Layout 自己渲染的「首页」那条也用它） |
| `.mobile-card*` | `styles/mobile-list.scss` | PC 页在 <768px 时的表格降级卡片（monitor/job） |

**PC 卡片同理**：`ptSubscription` 的订阅卡曾自造过一整套 `.sub-card / .sub-header / .sub-row / .sub-actions`，把 `.item-card` 的边框、圆角、hover 阴影、可点选态逐条重写了一遍，已退回 `.item-card item-card--compact` + `.card-header/.card-row/.card-footer`，只留海报横排（`.sub-main/.sub-poster`）、进度条（`.sub-progress`）、开关行（`.sub-switches`）这些真正特有的私有类。**网格里的加载条与空态已由 `list.scss` 统一横跨整行**（`.card-grid > .v-empty-state / > .v-progress-linear` 挂 `grid-column: 1 / -1`），页面里不要再各写一份。它们是网格的直接子元素，没有这条就只占一条轨道（≈300px），而 `v-empty-state` 会在自己的盒子里居中，于是整块空态挤在左上角那一格里——屏幕越宽越离谱（2560 的屏上只占 14% 宽度，看着像内容渲染错位而不是「这里没有数据」）。这条漏掉不报错、只是位置不对，实际 6 个卡片网格页全都漏了，其中 `ptDownloadRecord` 还只给加载条补了、没想到空态是同一个问题。

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
- **不作为表单字段的勾选框一律用 `v-checkbox-btn`，不要用 `v-checkbox`**（移动端卡片角标已全部收口，`views-mobile` 里只剩弹窗表单里那一个真·表单字段）。
  后者是**表单字段**：内部套一层 `VInput`，带来 min-height、label 的 `opacity: .6`、
  以及 details/hint 行的预留空间。把它放进批量工具条、卡片角标、全选行这类紧凑位置，
  就得写 `.v-selection-control { min-height: auto }` + `.v-label { opacity: 1 }` 去压——
  **需要写覆盖样式本身就是选错组件的信号**。`v-checkbox-btn` 直接渲染 `VSelectionControl`，
  没有那层外壳（`v-data-table` 表头的全选用的就是它），`label` / `indeterminate` /
  `density` 一样都不少，且没有 `hide-details`（那是 `VInput` 的 prop，不需要）。
- **`PageHeader`**：每个业务页顶部都要有。标题与描述**同一行**（图标 32px）——竖排时这块要占 57px，而列表页的首行数据本来就已经在半屏以下。
- **搜索区的输入框一律写 `hide-details`**（PC 的 `.search-fields`、移动端的 `MobileSearchPanel` 插槽）。
  不写的话 Vuetify 会给每个输入框在下方预留 details/hint 行（约 22px），移动端再叠上
  `.search-panel-body` 的 `margin-bottom: 12px`，四个字段就多出近百像素空白，观感上「过于松散」。
  审计时移动端 10 个页面共 32 处漏写、PC 端一处不漏——差别只在有没有照着已有页面抄，
  所以这条由 `design-system.spec.ts` 的「搜索区紧凑度」两条用例守着，不靠自觉。
  搜索区字段没有校验规则（带 `rules` 的都在弹窗表单里），`hide-details` 不会吞掉错误提示。
- **移动端日期区间**：`.date-range-fields` 里两个字段都要挂 `class="date-field"`（`flex: 1` 挂在这个类上，
  不写两个日期框不会等分），中间放 `<span class="date-range-sep">-</span>`。
- **「全选」是操作栏里的一个文字按钮，不是勾选框，也不单独占一行**。
  位置固定在**「取消」的正前面**（PC 的 `.batch-toolbar`、移动端的 `.batch-bar`；
  卡片网格页没有「取消」时放在 `.action-left` 末尾），样式与同一条栏里的「取消」完全一致
  （`variant="text"`，尺寸跟随该栏——批量条 `size="small"`，卡片网格页的操作栏用默认尺寸），
  统一挂 `.batch-select-all-btn`。文案随 `isAllPageSelected` 在「全选」/「取消全选」之间切换。
  走过两版弯路：先是 `v-checkbox`（表单字段，得写一堆覆盖样式压 `VInput` 外壳），
  再是列表上方单独一行 `v-card` 包着的勾选框——两版都因为「与周围操作按钮不是一种东西」而显得突兀。
  结论是**批量操作区里只有按钮**，不要混进表单控件。
- 卡片一律用 `<v-card>`，不要手写 `background: var(--osr-surface) + border-radius + box-shadow`。
- **表格「操作」列宽度上限 260px，装不下的动作收进「更多」菜单**（`v-menu` + `variant="text"` 的
  「更多 ▾」按钮，形态照抄 `ptSubscription`）。列表页表格的列宽是加法：勾选 48 + 内容列 + 状态列
  + 时间列 170 + 操作列，操作列一超标整张表就宽过内容区，页面出现横向滚动条。`renameDetail`
  曾经把 5 个动作平铺成 460px，整表 1248px，1280 宽的屏幕上必然溢出。**破坏性动作优先进菜单**
  （删除/清理这类点错了要命的，收起来反而更安全），留在外面的是高频的正向动作。
- **表格里的「A → B」对照一律竖排，用 `.path-box`**（`.path-row` + `.path-label--src/--dst`
  + `.path-name` + `.path-text`，见 list.scss）。不要自造左右并排的对照块：并排把一行宽度对半劈，
  两侧同时触发省略号，同样的信息要多占一倍列宽才看得清。`renameDetail` 的 `.rename-compare`
  就是这么来的，已删除。

### 弹窗
- PC 三档：`max-width="480"`（确认类）/ `600`（表单类）/ `900`（数据表类）
- 移动端统一 `width="92%"`
- 次要按钮（取消/关闭/测试连接）统一 `variant="outlined"`，主按钮 `variant="flat"`
- **上面两条不要在页面里手写，套 `components/dialogs/FormDialogShell.vue`**：它按
  `stores/app.ts` 的 device 自己选宽度档位，并把标题、取消/确定、左侧次要动作插槽
  （`#extra`）一并收进去。宽度**不做成 prop**——判据与 `createDeviceView` 选哪一端实现
  是同一个（`MOBILE_MEDIA_QUERY`），交给调用方传就多出一个可以传错、且传错了也不报错的地方。

### 大页面的拆法

`ptSubscription`（PC 1322 / 移动端 1328 行）、`monitor/job`、`system/config`、
`dashboard/desktop` 已经拆过一轮。三条经验：

**1. 状态多的页面先解决「子组件怎么拿到状态」，再谈拆。** 订阅页从 composable 解构出
90 多个标识符，6 个弹窗全靠它们；直接拆组件就是每个弹窗塞 20~30 个 props。做法是
`composables/ptSubscriptionContext.ts`：页面调 `usePtSubscriptionProvider(...)`（建实例 +
provide + 原样返回），子组件 `usePtSubscriptionContext()` 取**同一个实例**。
**子组件绝不能自己再调一次 `usePtSubscription()`**——那会拿到另一份互不相通的状态，
现象是「列表里点进度，弹窗里什么都不发生」。provider 的名字必须以 `use` 开头，
理由见下一条。

**2. 页面一拆，几条守护用例的扫描范围会跟着缩水，而且不会有任何报错。** 这轮踩了四次：
- `device-parity.spec.ts` 扫的是 `const { … } = useXxx(` 这个形状。改成
  `const ctx = usePtSubscription(); const { … } = ctx` 后，页面自己的解构就不在范围内了；
  它读的又只有 `index.vue`，动作搬进子组件后会被报成「这一端少了 9 个功能」。
  现在它连页面目录下的子组件一起读。
- `template-class-coverage.spec.ts` 同理已扩到 `views/**/*.vue`；**样式必须跟着模板搬**，
  留在页面 `<style scoped>` 里对子组件根本不生效。实测搬漏过两次（`.config-item` 整块、
  `.detail-table` 两层嵌套），都是这条用例逮到的。
- `design-system.spec.ts` 的写死色值白名单、`ptSubscription/__tests__/style-tokens.spec.ts`
  的 `readFileSync('../index.vue')`，都按新的文件位置改过。后者现在读整个页面目录。
**拆完一定要跑一遍完整单测**，这四条都不是靠肉眼能发现的。

**3. 拆出来的组件里不要 `v-model="item.xxx"`。** 卡片变成子组件后 `item` 是 prop，
`v-model` 直接写 prop 会被 `vue/no-mutating-props` 拦下。订阅卡的两个开关是「乐观更新 +
失败回滚」，这套逻辑已经挪进 `toggleAutoSearch(row, value)` / `toggleUpgrade(row, value)`
（持有 taskList 的那一侧负责改值），模板退回 `:model-value` + 事件。

当前形态：
```
views/openlist/ptSubscription/     index.vue + SubscriptionCard.vue + dialogs/(6 个)
views-mobile/ptSubscription/       同上（两端弹窗布局不同，各留一套）
views/monitor/job/                 index.vue + JobLogDialog.vue（日志弹窗自带查询/分页/详情）
views/system/config/               index.vue + ConfigItem.vue + configMeta.ts（配置目录是数据表，不是逻辑）
views/dashboard/                   desktop.vue + PtOverviewCard/RecentFailuresCard/QuickLinksCard（各自取各自的数）
```

### PC 列表页外壳

与移动端那轮收口对应的 PC 侧，一次专门的重构收口的。

**搜索区是 `SearchPanel`，默认收起，按页记住**（`useSearchPanel`，localStorage
`osr-search-panel`）。收起是量出来的决定：1280×800 上首行数据原先在 **y=403**——
顶栏 48 + 页头 57 + 搜索卡 122 + 操作条 36 + 表头 56，半屏都不是数据。搜索卡默认收起 +
页头压成一行 + 面包屑进顶栏之后是 **y=244**。字段作为默认插槽传进去，`ref="queryRef"`
挂在组件上（它 `defineExpose` 了 `resetValidation`，composable 的重置要靠这条链路）。
**`design-system.spec.ts` 里「搜索区紧凑度」那条用例的起止标记跟着改成了
`<SearchPanel>`**——留着旧的 `.search-fields` 标记的话，`section()` 找不到片段，
用例会变成永远通过的空检查。

**表格接线是 `useDataTable`**：承接选中行的本地 ref、转交给 composable、翻页、换每页
条数。这四件事原先在 10 个页面里逐字重复、每页约 20 行，而其中没有一处是页面自己的判断。

**每页条数档位统一走 `ITEMS_PER_PAGE_OPTIONS`（`[10, 25, 50, 100, 1000]`），10 个 PC
列表页都要绑 `:items-per-page-options`**。Vuetify 的默认档位末位是「全部」（value `-1`），
而后端 `BaseController#selectPage` 拿到 -1 会**收敛成 1000 条**（记录表可达数万行，整表
返回会让前端渲染卡死）——于是界面上写着「全部」、实际只回 1000 条，`total` 又是真实总数，
用户看到的是「选了全部却还在分页」，只能怀疑是不是漏了数据。末档显式写成 1000 就没有这层
落差。后端那条 -1 → 1000 的兜底保留着，挡的是直接调接口以及别处再传 -1 的情况。

**表格页的批量操作在 `.batch-toolbar` 里，不在 `.action-bar` 里**。改造前勾中一行，
页面上唯一的变化是几个批量按钮由灰变亮，**全页搜不到「已选」二字**；而选中集是页面局部
ref、翻页不清空，「批量删除」可能作用在已经翻过去、看不见的行上。现在选中后才出现这条
（吸顶），带条数与「清空选择」，形态与卡片型列表页一致。`.action-bar` 只留页面级动作
（新增 / 立即扫描）与搜索开关。

**「操作」列最多留 2 个按钮，其余进「更多 ▾」**（`.more-actions-trigger` +
`.more-actions-danger`，样式在 `list.scss`）。这条约定早就写着，但只有 3 个页面落实：
实测同步记录/STRM 记录 3 个按钮 250px 挤在 260px 的列里，**折成两行、行高从 52 涨到 62**。
另有一个陷阱：多表格页刻意不挂 `.modern-table--fixed`（`table-layout: auto`），
此时表头里的 `width` 只是建议值——热门自动订阅页 9 列一挤就把声明的 260 压到 **101px**，
四个按钮折了四行、行高 113。**auto 布局下要用 `minWidth`**。

**表头吸顶的前提是让表格自己成为滚动容器**（`.modern-table .v-table__wrapper` 的
`max-height: calc(100vh - 280px)`）。只写 `position: sticky` 是没用的：Vuetify 的 wrapper
是 `overflow: auto`，sticky 会粘在这个不滚动的祖先上，表头照样跟着整页滚走。
窄屏（<768）走 `.mobile-card*` 表格降级，不参与这条。

**选中态的两个标志叫 `noneSelected` / `notOneSelected`**（原名 `multiple` / `single`，
RuoYi 遗留）。`:disabled="multiple"` 字面读作「多选时禁用」、实际是「没选时禁用」，
全站 23 处都得在脑子里做一次取反。

**输入即搜索在 `useTaskList` / `useRecordList` 里，不在页面里**。改造前只有 3 个页面自己
写了防抖 watch，另外 14 个必须点「搜索」——同一套界面两种反馈，用户会以为某些页面卡住了。
去重靠「上次真正发出去的条件」指纹：`handleQuery`/`resetQuery` 会立即查一次，不比较的话
300ms 后 watcher 还会照着同样条件再打一次，**每次搜索发两个请求**。`pageNum`/`pageSize`
不参与指纹（翻页不是筛选变化）；`useRecordList` 还要把 `dateRange` 算进去——它写进
`queryParams.params` 是在 `handleQuery` 里发生的，只看 `queryParams` 的话改日期不触发查询。
`e2e/mobile.spec.ts` 的「返回时保留筛选」用例因此要先等这次自动查询落地再离开，
否则请求计数变成时序相关（单跑通过、并行跑偶发失败）。

**顶栏放面包屑（分组 / 页面），用户名取真实值**。面包屑刻意不是重复一遍页面标题：菜单
收敛成两级后页面本身完全不体现自己属于哪一组，而 PT 那四组恰恰靠分组才分得清。用户名与
头像首字走 `useCurrentUser`——两个 Layout 原先把「管理员」「管」写死在模板里，而
`userInfo.userName` 一直有值（首页就是这么取的）。

**PC 侧边栏的分组可折叠，默认只展开当前页所在那组**（`useSidebarGroups`，localStorage
`osr-sidebar-groups`）。菜单摊平后 37 行 1604px，而 1280×800 只装得下 17 行，PT 四组全在
折叠线以下。**rail 态（64px）下忽略折叠、平铺所有图标**——标题都藏起来了还折叠的话，
那条 64px 的图标带上一个图标都不剩。这与移动端抽屉保持平铺不矛盾：抽屉是临时浮层、
开一次点一下就关，多一层展开就是多一次等待；侧边栏常驻，值得让用户收起不用的部分。

### 移动端外壳（导航 + 列表页骨架）

一次专门的重构收口的，改之前先读完这一节。

**列表页骨架只有 `MobileListPage` 一份**。它管四样东西：`.mobile-page` 容器、`.task-list`
容器、顶部加载条、底部空态——它们之间的位置关系（加载条必须在列表容器内、空态必须与加载条
互斥）没有任何一页需要自己决定，而这四行原先在 17 个页面里各写一遍。页面结构固定为：
```
<MobileListPage :loading="loading" :empty="!loading && list.length === 0" empty-title="暂无X">
  <template #head>  搜索面板 / 批量条 / 常驻筛选  </template>
  卡片（默认插槽，直接放在 .task-list 里）
  <template #foot>  分页 / 弹窗 / 底部面板  </template>
</MobileListPage>
```

**批量条是 `MobileBatchBar`，并且吸在屏幕底部、盖住 tab 栏**。原先它跟着内容滚、排在搜索
面板下方，滚到第 20 张卡片再勾选时操作按钮已经在屏幕外了；「选择模式接管底栏」也是唯一
不用额外补内距的方案——内容区本来就为 tab 栏留了 `--osr-mobile-tabbar-height`。组件把
「已选 N 项」「全选」「取消」三件固定的东西收进去（**「全选」紧挨「取消」前面**这条约定
从此不靠人记），页面只用默认插槽给自己的动作按钮。背景必须不透明，否则透出下面的 tab 栏。

**卡片「更多」面板是 `MobileActionSheet` + `useActionSheet()`**。后者提供
`sheetOpen / sheetTarget / openSheet / run`，`run(() => handleX(sheetTarget))` 负责执行完
自动关面板——原先每个按钮都要手写一句 `xxxOpen = false`，漏写就是点完不关。

**底部 tab：4 个可跳转 + 第 5 格固定「更多」**。「更多」打开侧边抽屉——抽屉原先只有左上角
汉堡键一个入口，那是单手持机最难够到的位置，而 PT 那 12 个页面全都只能从它进。哪四个页面
上底栏由用户定（`useMobileTabs`，存 localStorage `osr-mobile-tabs`，入口在抽屉底部的
「自定义底栏」）：默认仍是首页/同步记录/STRM记录/重命名，但「最常用的四个」本就因人而异。
三条别改坏的：**tab 的 path 一律按 `meta.componentKey` 反查**（后端菜单 path 有
`/openlist/xxx` 与 `/openliststrm/xxx` 两种前缀，写死会跳 404）；**配置里指向已不存在的
菜单要丢掉而不是渲染出来**（改权限/删菜单后会留死链）；**上限 4 个不能放宽**——Vuetify 给
底栏按钮的 min-width 是 80px，六个按钮在 320px 机型上会把整页撑出横向滚动条（`.tabbar-item`
已经放开 min-width 并把标签压到 11px，这是留给第 5 格「更多」的余量）。
不在 tab 上的页面把「更多」点亮，底栏四个全灰会让用户失去「我在哪」的定位。

**抽屉里的菜单与 PC 共用 `SidebarMenuItem`**：分组渲染成一行灰色标题 + 子项平铺，不是
折叠面板。移动端曾用 `v-list-group` 手风琴，但全库 9 个分组 25 个叶子平铺后也就 30 多行，
换来的是「开抽屉 → 找分组 → 展开 → 点项」三次点击加一次动画等待；那份绑在 `:opened` 上的
展开态还是 computed（受控属性），用户手动展开的其它分组会在下次路由变化时被强制收起。
分组标题的显隐由调用方传 `showGroupLabel`（PC 收成 rail 时藏起来），**组件自己不读 store**
——`App.vue` 在移动端会调 `closeSidebar()`，读 store 的话移动端标题会跟着一起消失。

**退出登录收在头像菜单里**，与 PC 一致。它原先是紧挨 28px 头像的一个裸 `log-out` 图标，
两个热区间距只有 8px；破坏性动作进菜单这条约定（见下方表格操作列那段）在这里同样成立。

**设备判定走 `MOBILE_MEDIA_QUERY`（`stores/app.ts`）**，不是 `window.innerWidth < 768`。
第二个条件 `(max-width: 926px) and (pointer: coarse)` 专门管**手机横屏**——iPhone 14 Pro Max
横过来是 926×428，只看宽度会被判成 desktop，于是 220px 侧边栏加一张宽表格挤在 428px 高的
屏幕里；`pointer: coarse` 把它限制在触摸设备上，笔记本缩窗口到 900px 仍是 PC 布局。
`change` 与 `resize` 两个事件都听：iOS 14 之前的 Safari 只有 `addListener`、没有
`addEventListener('change')`，只挂 change 在那些设备上等于旋转屏幕不换布局。兜底不贵——
回调只读一个布尔量再写回 store，值没变 Vue 不会重渲染。

**返回时恢复滚动位置**由 `router` 的 `scrollBehavior` 负责（只在有 `savedPosition`，也就是
浏览器/手势返回时恢复，其余导航回到顶部）。列表页本来就带 keep-alive，筛选条件和页码都还在，
唯独滚动位置每次归零。恢复要延一帧：页面组件是异步加载的，立即滚会因为文档还没那么高而被截断。

### 表单弹窗两端共用（`components/dialogs/`）

**新增/编辑表单弹窗只有一份，PC 与移动端共用**，两端页面各自 `<XxxFormDialog />` 一行带过。
已覆盖 10 个页面：strmTask / copyTask / renameTask / ptIndexer / ptDownloader / ptMediaServer /
ptTorrentBlacklist / ptAutoAddRule / ptTransferRule / wecomUser。

**为什么只合弹窗、不合整页**：两端真正不同的是**列表外壳**（PC 是 `v-data-table` 表头排序/
表头全选/`v-pagination` 或卡片网格 + 工具栏，移动端是单列卡片 + FAB + 吸底批量条 +
`MobileActionSheet` + `MobilePager`），把它塞进一份模板就是插满 `v-if="isMobile"`——那不是
一套页面，是两套页面写在同一个文件里。而弹窗**本来就逐字相同**，两端唯一的真实差异只有宽度。
实测收口前 20 对页面平均有 53% 的行在对侧逐字重复，其中弹窗是重复得最彻底的一块。

**子组件怎么拿状态：`composables/pageStateContext.ts`**（`ptSubscriptionContext` 的通用版）。
页面写 `const { … } = usePageStateProvider(useXxx(…))`，弹窗写
`usePageState<ReturnType<typeof useXxx>>()` 取**同一个实例**。不走 props 是因为表单弹窗要
`v-model="form.xxx"`，`form` 一旦是 prop 就会被 `vue/no-mutating-props` 拦下，绕过它等于把
十几个字段的双向绑定手写一遍。**子组件绝不能自己再调一次业务 composable**——那会拿到另一份
互不相通的状态，现象是「点修改，弹窗里是空的 / 填完提交没反应」。provider 的名字必须以 `use`
开头，理由见下条。

**两条守护用例的扫描范围跟着扩过，改之前先看一眼**（同「大页面的拆法」第 2 条那个坑）：
- `device-parity.spec.ts` 的 `readPage` 现在**跟随 import** 读到共用弹窗；不跟随的话
  `submitForm` / `handleTest` 这些动作在两端都读不到，覆盖面悄悄缩水且没有任何报错。
  共用件被两端同时读到，它贡献的动作在两侧互相抵消，不会误报。
- `template-class-coverage.spec.ts` 已把 `components/dialogs/**` 纳入扫描：**样式必须跟着
  模板搬**，留在原页面 `<style scoped>` 里对弹窗根本不生效，而那正是这条用例专治的事故。

**弹窗内部的窄屏适配用 `@media`，不要读 device**（`StrmTaskFormDialog` 的 `.override-row`）：
判据是弹窗的可用宽度，而手机横屏（926px）算 mobile 却有 850px 可用，按设备类型切会把它
一起压成竖排。

### PC / 移动端对齐
- 新增功能必须同时改 `views/` 和 `views-mobile/`；**表单字段写进共用弹窗，一次就是两端**。
- **但「一份响应式实现」是这几个页面的正规做法，不是漏做**：`ptFilterConfig` /
  `ptUpgradeConfig` / `system/config` / `monitor/job` / `monitor/log` 只有 `views/` 一份，
  在移动端靠 `@media (max-width: 768px)` 适配（`monitor/job` 走 `.mobile-card*` 表格降级）。
  一屏表单和一个日志终端拆两套只会把还在正常工作的代码复制一遍，此后每处改动都要改两遍。
  **看到它们没有 `views-mobile/` 对应文件不要去补**——先在移动视口打开看一眼，
  实测 393px 与 320px 下都无横向溢出、功能完整。
  它们由 `e2e/mobile.spec.ts` 的 `Responsive-only pages` 两个用例守着
  （渲染在 MobileLayout 里 + 无横向溢出 + 各自的内容标记可见），
  改这几个页面前后跑一下：`npx playwright test e2e/mobile.spec.ts --project="Mobile Chrome"`。
- 两端功能差异由 `device-parity.spec.ts` 比对 composable 解构出的动作集合；
  确有差异要在该 spec 的 `ALLOWED_GAPS` 里登记原因（登记本身就是一次评审）。
- **同一个 spec 还比对两端模板里的表单字段**（`v-model="xxxForm.字段"`，差异登记在
  `ALLOWED_FIELD_GAPS`）。动作那条看不见模板里的字段，而字段缺一个是**完全静默**的：
  实测 `views-mobile/ptIndexer` 少了 `hrEnabled`/`hrSeedHours`/`hrRatio` 三个字段，移动端
  **新建**索引器配不了 H&R；编辑已有记录时值靠 `form = { ...task }` 整体回填，连数据都不会
  被抹掉，接口与日志一切正常，只有把两端模板并排摆着逐字段数才发现得了。
  **表单对象名一并参与比较**——同一页常有 form / retryForm / batchForm 并存，只比字段名会让
  「PC 的 batchForm.a」与「移动端的 form.a」互相抵消。
- **`PAIRS` 清单要与 `router/index.ts` 里 `createDeviceView` 的清单一致**。漏登记的页面
  整个不参与对齐检查，且不会有任何报错——`ptTransferRule` 就这么漏了一段时间。
- 选择/分页这层交互外壳按设备不同是正常的，已在 spec 的 `SHELL_ONLY` 里排除。

## ANTI-PATTERNS
- 不要在组件中直接调用 `axios`，统一用 `src/api/` 中的封装
- 不要绕过路由守卫，权限校验在 store 中统一处理
- 不要在组件中写大量业务逻辑，抽到 composables/
- 移动端页面不要使用 PC 端组件 (Vuetify PC 组件)
- 每个页面都要考虑H5端的适配
- 列表页不要各自实现分页/搜索逻辑，复用 `useTaskList`/`useRecordList`
- **允许小数的 `type="number"` 必须写 `step`，体积字段的 GB↔字节换算只有 `composables/sizeUnits.ts` 一份**。
  不写 step 时浏览器默认 step=1，输入 1.5 会被判成非法值、整个表单提交不了——PT 过滤规则的
  体积下限/上限/偏好体积踩过一次，而报错只是输入框下方一句「请输入有效值」，很容易读成前端坏了。
  换算侧同样不能取整：`Math.round(bytes / GB)` 会把 500MB 这类阈值显示成 **0**，
  而 0 在过滤规则里的语义是「不限」——用户一保存，阈值不是变粗了，是静默失效了。
  step 的粒度（`0.01`）与 `bytesToGb` 的小数位必须一致，否则回填的值不满足 step 约束，
  浏览器会把一个刚刚存进去的合法值标成非法。提交侧必须 `Math.round` 到整数字节：后端字段是 Long。
- **规则对象 → Vuetify `:rules` 函数的转换只有一份：`composables/formRules.ts` 的 `toRuleFns`**。
  收口前它在 14 个页面里各写一份，还分化成 4 种实现：只判 required（ptAutoAddRule /
  ptTorrentBlacklist / wecomUser 六份）、required + pattern（ptMediaServer / ptTransferRule
  四份）、required + pattern + 数字下限（ptIndexer 两份）、required + 数字上下限但**不判
  pattern**（ptDownloader 两份）——每份恰好只覆盖「自己那个 composable 当前用到的规则种类」。
  于是往 `usePtMediaServer` 的规则里加一条 `min`、或往 `usePtDownloader` 里加一条 `pattern`，
  那条校验会**静默失效**：不报错、不告警，表单照常提交，只是校验没了。
  实现里有一条容易改坏：**非必填字段留空必须早于数字判定放行**——`Number('') === 0`，
  不挡的话一个 `min: 1` 的选填字段在留空时报「不得小于 1」，用户没法把它清空。
  `composables/__tests__/formRules.spec.ts` 逐种规则各钉一条。
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
