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
├── composables/            # 组合式函数 (useTaskList, useRecordList, useDataTable, useSearchPanel, usePtStats, useEchart, useSidebarGroups, useBreadcrumb, useCurrentUser, useThemeMode, usePageTransition, useMenuLinks, useActionSheet, useMobileTabs, useMobileChrome, useMobilePageAction, useRecentPages 等)
├── layouts/                # 布局组件 (DesktopLayout, MobileLayout)
├── router/                 # 路由配置 (动态路由)
│   └── index.ts
├── stores/                 # Pinia 状态管理
│   ├── app.ts              # 应用全局状态 (设备检测、侧边栏)
│   └── user.ts             # 用户状态
├── styles/                 # 全局样式 (tokens.scss 设计令牌, motion.scss 动画库, surface.scss 深度/玻璃层,
│                           #           list.scss PC 列表公共, mobile-list.scss 移动端列表公共,
│                           #           mobile-chrome.scss 移动端外壳（悬浮底栏/更多面板）, menu.scss 侧边菜单)
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
| 状态管理 | `src/stores/` | Pinia store (app/user) |
| 布局 | `src/layouts/` | DesktopLayout / MobileLayout |
| 两端共用的表单弹窗 | `src/components/dialogs/` | `FormDialogShell` + 10 个 `XxxFormDialog`，见下方「表单弹窗两端共用」 |
| 移动端组件 | `src/components/mobile/` | MobileListPage(外壳), MobileSearchPanel, MobileBatchBar, MobileActionSheet, MobilePager, FullTextDialog, MobileTabBar, MobilePageAction, MobileMorePanel, MobileTabSettingsDialog |
| 移动端外壳/导航 | `src/layouts/MobileLayout.vue` + `components/mobile/MobileTabBar.vue` + `MobileMorePanel.vue` | 顶栏 / 悬浮底栏 / 「更多」面板，见下方「移动端外壳」 |
| MCP 令牌管理 | `views/system/mcpToken/index.vue` + `api/system/mcpToken.ts` | 只有 PC 一套（同参数设置）；明文令牌只在签发响应里出现一次，页面必须让用户当场复制 |
| PWA 配置 | `vite.config.ts` | VitePWA 插件配置 |
| 动效 / 深度 / 排版令牌 | `src/styles/tokens.scss` + `motion.scss` + `surface.scss` | 见下方「动效系统」「深度系统」「排版」 |
| 图表配色 | `src/plugins/echartsTheme.ts` | `chartBase()` / `lineSeries()` / `barSeries()` / `chartEmptyOption()` |
| PT 统计仪表盘 | `src/composables/usePtStats.ts` + `useEchart.ts` | 取数与图表选项两端共用，页面只负责容器与布局 |

## CONVENTIONS
- **自动导入**: vite-plugin-vuetify (autoImport) + unplugin-auto-import + unplugin-vue-components，`vue`/`vue-router`/`pinia` 与 `v-*` 组件无需手动 import
- **`@` 别名**: 指向 `src/` 目录
- **API 层**: 返回标准 `{ code, msg, data }` 格式，axios 拦截器自动处理
- **批量循环调同一个接口时传 `{ silent: true }`**（`request.ts` 给 `AxiosRequestConfig` 扩了这个字段）：拦截器照常 reject，只是不弹全局错误提示，由调用方最后汇总一句。不传的话十几条落空会弹十几个提示刷屏（先例：订阅页「批量立即补搜」→ `searchMissingApi(id, true)`）。它只管提示，不影响 401 刷新 token 那条路径
- **路由**: 后端动态返回菜单，前端根据权限生成路由
- **列表页模式**: 使用 composables (`useTaskList`/`useRecordList`) 封装增删改查 + 分页 + 搜索
- **移动端**: `views-mobile/` 独立于 `views/`，使用 `MobileSearchPanel` + `MobilePager` + `FullTextDialog` 组件
- **移动端页面**: 弹窗统一 `width="92%"`（不要再写 85%/90%/94%，也不要用 `max-width` 传百分比）
- **TypeScript**: 严格模式，`vue-tsc` 类型检查
- **CSS 变量 / 设计令牌**: 见下方「DESIGN SYSTEM」一节
- **暗色模式**: 顶栏 ThemeSwitch 切换 浅色/深色/跟随系统，`useThemeMode`（模块级单例）同步 Vuetify 主题 (osrLight/osrDark) 与 `<html data-theme>`，localStorage key `osr-theme` 持久化；切换时派发 `osr-theme-change` 事件供 ECharts 等 canvas 场景重绘（`osrCssVar()` 读取当前令牌值）；切换带**从点击位置扩开的圆形揭示**（View Transitions，见下方「动效系统」），调用方要把点击事件传给 `setMode(mode, event)`
- **列表页公共样式**: PC 用 `styles/list.scss`，移动端用 `styles/mobile-list.scss`，**禁止在页面里复制这些类**；各页只保留特有子规则（见下方 DESIGN SYSTEM）
- **PageHeader**: 每个业务页顶部都要有 `PageHeader`（图标+标题+描述+操作区），不要自造 page-header 样式
- **移动端页面标题只有一处：`MobileLayout` 的大标题（图标 + 标题）**，图标取路由 `meta.icon`（即 `sys_menu.icon`），没配退化成 `menu`。`views-mobile/` 页面**不要自己再写标题**；退回 PC 实现的页面（参数设置 / MCP 令牌 / 定时任务 / 过滤规则 / 洗版规则）里的 `PageHeader` 在 MobileLayout 内只渲染操作区、没有操作区时整块不渲染——曾经这 5 页是「外壳大标题 + PageHeader」双标题，其余页只有一个不带图标的标题。判断「是否在移动端外壳里」用 `useInMobileLayout()`（inject），**不要在 PageHeader 里读 device store**：单测直接挂载 PC 页面时没有 Pinia，会连带 40 多条用例失败
- **页面切换动画走 `usePageTransition`（WAAPI，只做入场不做离场），仍然不要包 `<transition>`**: 两个 Layout 里都刻意没有 `<transition>`——在「`<KeepAlive>` 与裸 `<component>` 交替 + 页面组件异步加载」这个结构下，过渡类不会被清掉、离场过渡收不到结束事件，每导航一次旧页面就留在新页面下方越堆越多（`mode="out-in"` / `:duration` 都压不住）。现在的做法见下方「动效系统」一节
- **Dashboard**: PC 统计卡用 `MiniTrend`（SVG sparkline，颜色走 `--osr-*` CSS 变量自动适配暗色，折线带 `pathLength="1"` 的描边动画）；统计数字一律套 `AnimatedNumber`（rAF 滚动，自己解析 `85%` / `--` / `12 分钟` 这类混合形态）；快捷入口统一用 `useMenuLinks`（菜单树拍平，PC/移动共用，禁止写死路径）；PT 概览/失败列表/待办提醒/快捷入口各自是 `views/dashboard/` 下的独立卡片组件，自己取自己的数。**首页有三条不要改坏的**：（1）**统计接口失败必须显示错误态 + 重试，不能退化成一排 0**——用户会把它读成「系统很干净」；成功率无任何 COPY/STRM 记录时后端返回 `null`（不是 0.0），前端显示 `--`，0% 是「全部失败」的真实含义；（2）**欢迎区的日期与一言只有 `composables/useDashboardHeader` 一份**，PC 与移动端共用，日期是 ref、页面重新可见时 `refreshDate`；（3）**「待办提醒」数据只有 `composables/useDashboardTodo` 一份**（缺集体检分档数 + 异常索引器数），两路 `allSettled` 各自独立，**取失败要如实说没取到，不能当成「暂无待办」**。趋势缓存键是 `类型:天数`，页面重新可见且距上次加载超过 60 秒才自动刷新（刷新时保留旧数字、不闪骨架屏）

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
`--osr-glass-*`（顶栏/菜单/弹窗/移动端悬浮底栏与「更多」面板的玻璃层）、`--osr-ambient`
（铺在 `.v-application::before` 上的环境光，用伪元素是因为 Vuetify 会重写
`.v-application` 的 background、写在上面会被盖掉）。

**玻璃是三件套：`blur` + `saturate` + 顶亮底暗的一对 inset 高光**
（`--osr-glass-bg` / `--osr-glass-blur` / `--osr-glass-spec-top` / `--osr-glass-spec-bottom`）。
`saturate` 不能省——只做 blur 会让透上来的内容掉一档饱和度、整块发灰，那是磨砂塑料不是玻璃；
而那对 inset 高光是**「厚度」的唯一来源**，删掉之后悬浮底栏会塌回一张糊了的贴纸，暗色下尤其
明显。悬浮 chrome 的投影用 `--osr-glass-shadow`，与 `--osr-shadow-*` 是两回事：那三档给的是
「贴在页面里的卡片」，这一档要把整块抬离内容，扩散更大、更靠下。

**`prefers-reduced-transparency` 的兜底也做在令牌层**（`tokens.scss` 末尾）：玻璃退回不透明
表面，所有走 `--osr-glass-*` 的地方一次全覆盖，新增玻璃表面自动受管。**那条 media 里的
选择器必须把 `:root[data-theme='dark']` 一起写上**——暗色覆盖块是 (0,2,0)，只写 `:root`
（0,1,0）的话本块在暗色主题下整个失效，而暗色恰恰是玻璃最透的一档。iOS 的「降低透明度」
是个真有人开的开关，不是假想情况。

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
装饰 + 玻璃面板）。它是一个**时刻**而不是一个工作区。六条：
- **品牌区只有两层：图标 + 全称，不要把 `OSR` 那个大标题加回来。**
  `public/icons/` 那枚图标本身就是 OSR 标志（三个字母画在里面），外面再写一遍简称、
  下面又写一遍全称，同一件事说了三遍。现在留下的一层既是标志又是名字。原来挂在简称上的
  品牌渐变移到了全称上——它是这一屏唯一的品牌色落点，不能跟着一起删。全称是 19 个等宽
  字符的单行（`white-space: nowrap`），字号在 480px / 360px 两档各降一级：不降不会触发
  横向滚动条，只是左右各顶进内边距几个像素，看起来像没对齐。
- 卡片用 `<v-theme-provider theme="osrDark">` 包起来，**不要手写颜色覆盖**——
  里面全是 Vuetify 组件，让它们自己按暗色主题渲染才不会漏掉聚焦态、错误态这些分支。
- **那个 provider 必须带 `with-background`，尽管这里并不想要它的背景。**
  它的实现是 `if (!props.withBackground) return slots.default?.()`——不带这个 prop
  就**一个元素都不渲染**，只靠 inject 传主题。Vuetify 组件照样会把 `v-theme--osrDark`
  挂到自己身上、拿到正确的 CSS 变量（`--v-theme-on-surface` 查出来是对的），但
  **`color` 是继承属性**，中间没有元素重新锚定，面板里的输入文字与 prepend/append 图标
  会一路从 `.v-application` 继承下去——而它在用户开浅色时是 `osrLight`，于是
  `rgba(26,26,26,.87)` 的近黑色压在深色玻璃输入框上，对比度约 1.1:1，基本看不见。
  补上这一环的正是 `.v-theme-provider` 自带的那条 `color: rgb(var(--v-theme-on-background))`。
  **这个 bug 极其隐蔽**：跟随系统主题的默认设置下，**深色系统的用户完全看不到**，
  而 CSS 变量查出来全是对的，只有去看 `getComputedStyle(input).color` 才发现是继承来的。
  不想要的那层不透明背景用 `.login-theme-scope { display: contents }` 消掉——
  没有盒子就不画背景，元素却仍在继承链上；顺带它也不会成为 `.login-stage` 的 flex 子项
  去顶替 `.login-panel` 参与尺寸计算。`e2e/login.spec.ts` 那条**在浅色下**测前景色的用例
  钉住这件事，钉在浅色是关键，深色下它恒过。
- **提交走 `<v-form @submit.prevent>` + 按钮 `type="submit"`，不要用 `@click`。**
  两件事：`VBtn` 默认渲染成 `type="button"`，而表单有两个以上文本控件且没有提交按钮时
  浏览器**不做隐式提交**——按钮挂 `@click` 的那一版，在用户名框里按回车是彻底没有动静的
  （密码框当时靠一句 `@keyup.enter` 单独兜着）；而 `.prevent` 也不是顺手加的，
  `VForm.onSubmit` 在校验通过且事件未被 preventDefault 时会调 `formRef.submit()`
  走**原生提交**，那是一次整页刷新。密码显隐用 `#append-inner` 插槽放 `v-btn`，
  不要用 `append-inner-icon` + `@click:append-inner`——后者渲染出来是个裸 `v-icon`，
  鼠标能点、键盘 Tab 不到，而这颗按钮恰恰是给看不清自己输了什么的人用的。
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
| `.mobile-page` `.task-list` `.task-card` `.batch-bar` `.card-actions` `.drawer-actions` `.date-range-fields` | `styles/mobile-list.scss` | 移动端页面骨架与卡片（骨架三件套已被 `MobileListPage` 包起来，页面不再直接写） |
| `.mobile-tabbar` `.tabbar-item` `.tabbar-pill` `.tabbar-label` `.mobile-page-action` `.more-panel*` `.more-tile*` `.more-group-label` `.mobile-bigtitle` `.appbar-title` | `styles/mobile-chrome.scss` | 移动端外壳：悬浮底栏 + 「更多」底部面板 + 大标题（**面板会被 Teleport 走，样式不能写进组件的 scoped 块**） |
| `.menu-item` `.menu-group-label` | `styles/menu.scss` | PC 侧边菜单项（DesktopLayout 自己渲染的「首页」那条也用它；移动端已改用「更多」面板，不再用这套） |
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
- **「去 TMDb 看一眼」外链只有 `components/TmdbLink.vue` + `composables/tmdbLink.ts` 一份**，
  PC 与移动端、6 个页面 14 个落点共用（重命名明细、订阅卡与进度弹窗、选片弹窗、缺集体检、
  追剧日历的两个弹窗、热门自动订阅执行日志）。收口的理由不是"少写几行"，而是**大小写这一条
  必然漂移**：`rename_detail.media_type` 存小写 `tv`/`movie`，`pt_subscription` /
  `pt_auto_add_log` 存大写 `TV`/`MOVIE`（后端两条链路各有各的约定，不打算统一）。严格判等小写
  的实现拿到 PT 侧的数据会**恒返回 null**——不报错、不告警，只是那个链接一个都不渲染，
  而"这个按钮怎么没出来"是最难从代码审查里看出来的一类缺陷。四条不要改坏的：
  **（1）信息不足时整个组件不渲染**，调用方不必自己判（拼不出链接时留一个点不动的图标更让人困惑）；
  **（2）`@click.stop` 不是防御性写法**——订阅卡在批量模式下整卡点击 = 选中、选片弹窗整行点击 = 选片，
  不拦住的话点一下 TMDb 会顺带改掉选择状态；
  **（3）`episode` 只接受 TMDb 口径的集号**（`tmdb_episode_number`），本地季内相对号与 TMDb 主数据
  未必一致（长篇动画用绝对号，见 osr-openliststrm/.../pt/subscription/AGENTS.md「集号有三套」），拼进去会落到一个 TMDb 上不存在
  的集——追剧日历的 `CalendarEntry.episode` 正是本地号，所以那两处只深链到季；
  **（4）判断是不是电影一律看 `mediaType` 不看 `season`**：电影的 season 恒为哨兵 0，而剧集的
  特别篇也是第 0 季。形态有 `icon`（默认，卡片标题旁）/ `tag`（复用 list.scss 的 `a.record-tag`，
  重命名明细那排标签）/ `text`（把 TMDb ID 本身变成链接）三种，不要加第四种。
  **尚未覆盖的三处要后端先补字段**：下载记录（只有 subId/subTitle）、PT 统计仪表盘的 Top 活跃订阅
  与首页 PT 概览卡（`PtStatsActiveSubscriptionDTO` 无 tmdbId）、重命名一致性检查（要 join 回
  rename_detail）。
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

### 记录页（同步 / STRM / 重命名明细）

三个记录页共用 `useRecordList`，这一节是它们在列表外壳之外多出来的几件东西。

- **顶部统计条 `RecordStatusBar`**：「全部 · 各状态条数」，点一下即按状态筛选，再点回到全部。
  它与搜索区的状态下拉 `v-model` 绑**同一个字段**，两处永远一致。数字来自 `/stats`，
  **跟随搜索条件、忽略状态这一项**（点「失败 17」筛出来一定是 17 条）。计数为 0 的状态
  禁用而不是隐藏——隐藏会让统计条宽度随数据跳动。状态选项（`COPY_STATUS_OPTIONS` 等）
  是下拉、统计条、StatusChip 三处的唯一来源，不要在模板里再写一份 `[{ title, value }]`。
  同步记录的状态 4 叫「异常」不叫「未知」：具体原因已写在失败原因里，「未知」读起来像是显示出错。
- **「重试全部失败」与 TG 的同名指令走同一个后端方法**，一次最多最新 200 条，提示要说清剩余多少。
- **后台执行类动作的提示必须如实**（`batchRetryMessage` / `removeNetDiskMessage` /
  `retryAllFailedMessage`）：超过 20 条后端转后台、接口立即返回，一律说「成功」的话用户刷新后
  看到记录没变会以为没生效。这类动作之后 `refreshLater` 过 5 秒再静默刷一次。
- **有推进中的记录时自动静默刷新**（`isInProgress`，每 10 秒）；页面 keep-alive 停用、
  标签页不可见、或已无推进中的记录时停。**只给真正会自己变化的状态配**（同步记录的处理中），
  终态记录配上它就是一个永不停止的轮询。
- **失败原因显示在详情格最后一行**（`.record-fail-reason`，最多两行）、**只在失败 / 异常状态下显示**
  ——后端写成功时已清空原因，前端这层是兜底。重命名明细显示的是刮削失败原因 `scrape_msg`。
- **PC 详情抽屉 `RecordDetailDrawer`**：表格里路径必然截断，抽屉把全部字段摊开、路径带复制按钮。
  它是 `v-navigation-drawer`，**单测 mount 页面时要 stub 掉**（没有 `v-layout` 会抛
  `Could not find injected layout`）。移动端不做这一层：卡片本来就逐行展示、点开即全文。
  复制在 `http://` 局域网部署下不可用（clipboard API 只在安全上下文开放），要提示手动复制而不是静默失败。
- **重命名明细的识别结果标签**（`renameTags` / `tmdbUrl`，样式 `.record-tags` / `.record-tag`）：
  缺失的片段整段不写，不写「未知」。批量工具条里**破坏性动作收进「危险操作」菜单**，平铺的只留执行与刮削。
- **STRM 记录的文件类型**：图标按常见字幕扩展名判（`isSubtitleFile`，只用来挑图标），
  筛选走后端 `fileType`，用的是真实的扩展名配置。已成功的记录按钮叫「重新生成」而不是「重试」。

### PT 下载记录页

卡片网格页，但顶部统计条、推送时间区间这些记录页的东西它也都有（同样建在 `useRecordList` 上）。

- **状态 / 失败原因 / H&R 的标签与选项只有 `composables/ptDownloadRecordLabels.ts` 一份**。两端原先各写一套 switch，新增 `METADATA_TIMEOUT` 时要改两处，漏的那端显示成「其他原因」——不报错，只是悄悄说错。
- **路由带进来的 `subId` 不能写进 `defaultQuery`**：写进去「重置」会把它还原回来，从订阅页跳过来之后就再也回不到全部记录。现在是创建后单独赋值，配一条可关闭的筛选条（`subFilterLabel`，剧名随路由的 `subTitle` 带过来），重置与关闭都会 `router.replace` 掉地址栏里的 `subId`，否则一刷新又筛回去。
- **WebSocket 原地更新后状态变了要静默刷一次**（1.5 秒防抖合并）：统计条的数字和「当前筛选下还该不该出现这一行」都跟着变了。按状态筛选时，变成别的状态的那一行当场移除。
- **批量重试认领自己的 `batchId`**，别人发起的那批结果不提示。拉黑（单条 / 批量、种子 / 发布组）一律先过 `PtBlacklistDialog`、可填原因——拉黑发布组的影响面是该组今后所有种子，原先点一下就生效。

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
不用额外补内距的方案——内容区本来就为 tab 栏留了 `--osr-mobile-tabbar-occupied`。组件把
「已选 N 项」「全选」「取消」三件固定的东西收进去（**「全选」紧挨「取消」前面**这条约定
从此不靠人记），页面只用默认插槽给自己的动作按钮。样式（含它与悬浮底栏的几何对齐）在
`styles/mobile-list.scss` 的 `.batch-bar` 单源，几条硬约定见下方「移动端外壳」。

**卡片「更多」面板是 `MobileActionSheet` + `useActionSheet()`**。后者提供
`sheetOpen / sheetTarget / openSheet / run`，`run(() => handleX(sheetTarget))` 负责执行完
自动关面板——原先每个按钮都要手写一句 `xxxOpen = false`，漏写就是点完不关。

**底部 tab：4 个可跳转 + 第 5 格固定「更多」，组件是 `MobileTabBar`，样式在
`styles/mobile-chrome.scss`。** 哪四个页面上底栏由用户定（`useMobileTabs`，存 localStorage
`osr-mobile-tabs`，入口在「更多」面板底部的「自定义底栏」）：默认仍是首页/同步记录/
STRM记录/重命名，但「最常用的四个」本就因人而异。四条别改坏的：**tab 的 path 一律按
`meta.componentKey` 反查**（后端菜单 path 有 `/openlist/xxx` 与 `/openliststrm/xxx` 两种
前缀，写死会跳 404）；**配置里指向已不存在的菜单要丢掉而不是渲染出来**（改权限/删菜单后
会留死链）；**上限 4 个不能放宽**（第 5 格留给「更多」，320px 机型上五格已经到顶）；
**不在 tab 上的页面要把「更多」点亮**——PT 那四组 12 个页面全都不在底栏上，五格全灰会让
用户失去「我在哪」的定位。**类名 `.mobile-tabbar` / `.tabbar-item` 是 `e2e/mobile.spec.ts`
的定位锚点，改名那批用例会整片失败，而失败信息只会说找不到元素。**

**底栏是离边悬浮的玻璃胶囊，不是贴边全宽的 `v-bottom-navigation`。** 换掉那个组件是因为
它是 Vuetify 的**布局**组件：全宽 fixed、参与 `v-main` 的偏移计算，要改成悬浮就得一路和
它的布局系统对抗；它给按钮的 `min-width: 80px` 也曾把五格挤出 375px 的屏幕（首尾各被裁掉
一截，不报错、不出横向滚动条，只是边缘缺了一块）。现在是原生 `<button>` 实现。
两条关于**几何**的硬约定：
- **「给底栏让位」一律用 `--osr-mobile-tabbar-occupied`，不要用 `-height`。** 悬浮之后
  「栏高」不再等于「底部占掉多少」，后者还含离底间距与安全区。内容区的 `padding-bottom`、
  `.batch-bar` 的定位都按它算，用错的表现是按钮或最后一张卡片被压在栏下面点不到，
  而页面不报任何错。
- **`.batch-bar` 的几何必须与 `.mobile-tabbar` 逐项对齐**（同一批令牌算左右内距、离底距离、
  最小高度）。选择模式一开就是「一个浮着一个贴边」的话，读起来像两个不相干的控件叠在一起，
  而它本该是同一根栏换了个状态。它**背景不透明**（用玻璃会把下面的 tab 图标糊着透出来）、
  **圆角用 `--osr-radius-xl` 而不是 999px**（按钮多时会 wrap 成两行，胶囊圆角会把第二行
  首尾的按钮切进圆弧里）。

**页面主动作并在底栏右侧，不再是压在内容上的右下角悬浮按钮**（`useMobilePageAction` +
`MobilePageAction`，样式 `.mobile-page-action` 在 `styles/mobile-chrome.scss`）。页面调
`useMobilePageAction(() => ({ icon, label, onClick, loading }))`，底栏据此把右侧让出一个与栏
等高的圆钮位，收缩态两者一起变矮、整组向中间收。原先的 `.fab-add` 浮在内容上方，而内容区只为
底栏留了位——滚到底时正好盖住分页器右侧的「下一页」与每页条数，没有分页器的页面盖住最后一张
卡片的「更多」，不报任何错。五条别改坏的：
- **一页一个动作，不做成点开再选的菜单**，否则最常用的动作要多点一次、这颗按钮变成第二个「更多」。
  **判据**：这一页最常用、不需要先勾选、不是不可撤销的、页面长了要滚回顶部才够得着。现在是
  11 个「新增」+ 重命名一致性检查「立即扫描」+ 通知路由「保存」。刻意不放的：批量操作开关
  （进入选择模式后批量条会接管底栏，两者冲突）、跳转链接（缺集体检）、「重新加载」（会丢掉
  未保存的修改）、只在有数据时出现且影响面大的批量动作（缺集体检「为 N 条订阅开启自动补搜」）、
  一页里有两个的同类动作（重命名配置的两个保存）。
- **登记挂在 mounted/activated 与 deactivated/beforeUnmount 两对钩子上**。7 个列表页开了
  keep-alive，切走时只 deactivate 不 unmount，只挂 mounted 的话按钮会带到下一页（点下去执行的是
  上一页的新增），返回缓存页时按钮又没了。
- **按 owner 登记、按 owner 撤销，后登记的优先**。新旧页面的钩子在同一次刷新里先后执行，按
  「清空当前值」撤销会把新页面刚登记的按钮一起清掉。`useMobilePageAction.spec.ts` 钉住这两条。
- **批量条出现时主动作要主动收起**（`MobileBatchBar` 里调 `useMobileBatchBarPresence`），
  不能只靠批量条盖住它：键盘与读屏仍能聚焦到那颗看不见的按钮。
- **实心主色，不用玻璃**：玻璃圆钮贴着玻璃胶囊，读起来像底栏第六格，而它是「在这一页做一件事」
  不是导航。按钮只有图标，`label` 是它唯一的文字（读屏名称 + 长按提示），要写全（「新增索引器」）。

**底栏随滚动方向收缩**（`useMobileChrome`）：向下滚收窄成图标态，向上滚或回到顶部附近还原。
**判据必须是方向，不是「滚过没有」**——按后者做的话，任何一个滚过一次的列表页此后永远是
图标态，等于把文字标签删了，而标签正是新用户分得清这五格的唯一依据，且这个退化完全静默。
同一个 composable 还给出 `scrolled`（判据是**绝对位置**：大标题滚出视口没有），驱动顶栏
小标题的淡入与那条分隔线——两个布尔量判据不同，不要合并。

**左侧抽屉已删除，换成从底部升起的「更多」面板（`MobileMorePanel`，`v-bottom-sheet`）。**
抽屉的问题不是长得丑，是两件事：**手势方向**（入口在底栏最右格，按一下东西却从左边飞进来；
它另一个入口——左上角汉堡键——恰好是单手持机最难够到的一角）与**密度**（9 个分组 25 个叶子
平铺成 30 多行，要滚两屏，每行只有一个图标加两到六个汉字，没有任何可供快速定位的形状差异）。
面板是四列图标格，一屏半到底，顶部还放得下搜索框——列表形态没有地方放它，除非再占掉一整行。
四条别改坏的：
- **样式必须在 `styles/mobile-chrome.scss` 里，不能写进组件的 `<style scoped>`。**
  `v-bottom-sheet` 会把内容 Teleport 到 `.v-overlay-container`，scoped 的属性选择器够不着它，
  症状是「掀开来一片没样式的白板」。
- **数据走 `useMenuGroups()`（`useMenuLinks.ts`），它保留一层分组**；`useMenuLinks` 那份
  拍平的给底栏配置与快捷入口用。两者共用 `resolvePath`，各写一遍的漂移表现是「同一个菜单
  在底栏能跳、在更多面板里 404」。
- **首页必须由 `HOME_LINK` 补进来**：它是常量路由、不在后端 `getRouters` 下发的菜单树里。
  旧抽屉是把「首页」硬编码成第一条 `v-list-item` 的，换实现时最容易连着那行一起丢掉，
  症状是把首页从底栏移除后再也回不去。这个常量此前在 `useMobileTabs` 里就写了两遍，现已收口。
- **搜索同时匹配菜单名与分组名**：打「pt」要能一次筛出 PT 那四组，而它们的子菜单名
  （订阅管理、过滤规则、索引器…）里一个 PT 字样都没有——20260785 分组时特意去掉了那个前缀。

**面板顶部的「常用」是最近访问过、且不在底栏上的页面**（`useRecentPages`，存 localStorage
`osr-mobile-recent`）。**剔掉底栏那几个是关键**：它们本来就一触即达，摆进这一行只会把真正
需要走这个入口的页面挤出去——而这个面板存在的全部理由，就是底栏放不下的那 21 个。
分块顺序是**无标题的首块（首页）→ 常用 → 各菜单分组**：无标题那块夹在两个带标题的分块中间时，
读起来像是上一块漏了几个格子。

**顶栏没有汉堡键了**，左上角空出来的位置留给页面级动作比留给全局菜单合理。顶栏下方多了一行
**大标题**（`.mobile-bigtitle`），随内容一起滚走，滚过之后顶栏里的小标题才淡入（iOS 的做法；
两个标题同时出现是错态）。**刻意不做「高度收缩」动画**——那要在滚动回调里改一个参与布局的
属性、每帧触发重排，而效果与让它自然滚走几乎无异。

**`SidebarMenuItem` 现在只有 PC 在用**（分组渲染成一行灰色标题 + 子项平铺，不是折叠面板）。
分组标题的显隐由调用方传 `showGroupLabel`（PC 收成 rail 时藏起来），**组件自己不读 store**
——历史上它读过，而 `App.vue` 在移动端会调 `closeSidebar()`，于是移动端标题会跟着一起消失。

**退出登录收在头像菜单里**，与 PC 一致。它原先是紧挨 28px 头像的一个裸 `log-out` 图标，
两个热区间距只有 8px；破坏性动作进菜单这条约定（见下方表格操作列那段）在这里同样成立。

**设备判定走 `MOBILE_MEDIA_QUERY`（`stores/app.ts`）**，不是 `window.innerWidth < 768`。
第二个条件 `(max-width: 926px) and (pointer: coarse)` 专门管**手机横屏**——iPhone 14 Pro Max
横过来是 926×428，只看宽度会被判成 desktop，于是 220px 侧边栏加一张宽表格挤在 428px 高的
屏幕里；`pointer: coarse` 把它限制在触摸设备上，笔记本缩窗口到 900px 仍是 PC 布局。
`change` 与 `resize` 两个事件都听：iOS 14 之前的 Safari 只有 `addListener`、没有
`addEventListener('change')`，只挂 change 在那些设备上等于旋转屏幕不换布局。兜底不贵——
回调只读一个布尔量再写回 store，值没变 Vue 不会重渲染。

**这件事只有 `App.vue` 一处做，路由守卫不许碰。** `router.beforeEach` 里曾另写一份
`window.innerWidth < 768`，两套判据对手机横屏（926×428）结论相反：App.vue 判 mobile，
用户一导航守卫就翻成 desktop，**而 App.vue 此时收不到 change/resize**，布局就此卡在
desktop 直到用户转一次屏——不是闪一下，是持续判错，且没有任何报错。修法不是让守卫也用
`MOBILE_MEDIA_QUERY`（两处判据早晚漂移，而漂移的表现正是这个 bug 本身），是让守卫
彻底不碰设备判定：那 5 行连同 `useAppStore` 的 import 一起已删除。

**返回时恢复滚动位置**由 `router` 的 `scrollBehavior` 负责（只在有 `savedPosition`，也就是
浏览器/手势返回时恢复，其余导航回到顶部）。列表页本来就带 keep-alive，筛选条件和页码都还在，
唯独滚动位置每次归零。恢复要延一帧：页面组件是异步加载的，立即滚会因为文档还没那么高而被截断。

### 表单弹窗两端共用（`components/dialogs/`）

**新增/编辑表单弹窗只有一份，PC 与移动端共用**，两端页面各自 `<XxxFormDialog />` 一行带过。
已覆盖 10 个页面：strmTask / copyTask / renameTask / ptIndexer / ptDownloader / ptMediaServer /
ptTorrentBlacklist / ptAutoAddRule / ptTransferRule / wecomUser。

**为什么只合弹窗、不合整页**：两端真正不同的是**列表外壳**（PC 是 `v-data-table` 表头排序/
表头全选/`v-pagination` 或卡片网格 + 工具栏，移动端是单列卡片 + 底栏主动作 + 吸底批量条 +
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

### 错误边界

**`components/ErrorBoundary.vue` 包在两个 Layout 的 `router-view` 外面、外壳里面。**
在它之前应用里一个 `onErrorCaptured` / `app.config.errorHandler` 都没有：任意一个页面组件
在渲染或事件处理里抛出未捕获异常，Vue 会把**整棵组件树卸载**，用户看到一整块白屏，
控制台那行报错他既看不到也读不懂，唯一的自救手段是刷新——而刷新回到同一个页面、同一个错误。
四条不要改坏的：

1. **包在外壳里面，不是包整个 App**。顶栏、侧边栏、底部 tab 必须活着，用户才走得掉；
   把整页换掉的话除了刷新还是没有出路。
2. **路由一变自动复位**（`watch(() => route.fullPath, reset)`）。错误态属于「某个页面的
   这一次渲染」，跟着边界挂到下次刷新的话，用户点到别的菜单会继续看到上一页的错误。
3. **「重试」靠换 key 重建插槽，不是只把 error 置空**。后者出错的组件实例还在，Vue 复用它
   继续渲染，多半立刻再抛同一个错，按钮看起来像是坏的。包裹层是 `display: contents`，
   存在的唯一理由就是给插槽一个可换的 key，不参与布局。
4. **吞掉错误的同时要往控制台补一条堆栈**。`onErrorCaptured` 返回 false 阻止冒泡
   （否则 `main.ts` 的全局 handler 会把同一个错再报一遍），代价是控制台从此什么都没有，
   而堆栈正是排查时唯一有用的东西。

`main.ts` 的 `app.config.errorHandler` 是最后一道兜底，**只记不吞**：那里没有可展示的位置，
硬弹 toast 会在错误连发时刷屏。走到它的只剩边界自身出错、以及边界还没挂上去（外壳本身出错）。

**边界捕不到路由懒加载 chunk 拉取失败**——那发生在组件解析阶段，组件树里还没有这个边界。
那一类归 `router.onError` 管，见下一节。

### 旧 chunk 失效（`router.onError` + `router/chunkError.ts`）

重新部署后，开着的标签页手里还攥着旧 index.html 里那串带 hash 的文件名，而 Nginx 给
index.html 与静态资源都打了 `no-store`、镜像里的旧 chunk 文件已经没了。用户点一个还没访问过的
菜单，那次动态 import 404、vue-router 中止导航——**页面上什么都不发生**，只有控制台一行红字。
处理是「识别 → 硬刷新到目标路径 → 用 sessionStorage 标记防打转」。五条不要改坏的：

1. **判据是纯字符串匹配，各家浏览器措辞完全不同，漏一种就等于那种浏览器上整个不生效**，
   且表现与没写这段代码一模一样。Chromium `Failed to fetch dynamically imported module`、
   Firefox `error loading dynamically imported module`、Safari `Importing a module script failed`、
   Vite 的 CSS 预加载助手 `Unable to preload CSS for`——中间两条最初就是漏的。一律小写后比对
   （同一句话在不同版本里首字母大小写变过）。`router/__tests__/chunkError.spec.ts` 逐条钉住。
2. **判据要能吃非 Error 输入**。`error.message.includes(...)` 遇到 throw 字符串或
   `message` 不是字符串的对象时，会在**错误处理器内部**再抛一个 TypeError，把「这一次导航
   失败」升级成整个 `onError` 失效——此后任何 chunk 失效都不再有人兜底。
3. **写不进 sessionStorage 就必须放弃刷新，改为提示手动刷新**。标记是防打转的唯一凭据，
   而它得活过一次 location 跳转、只能落在 sessionStorage 上（模块级变量随刷新归零）。
   写不进去还照样刷的话，刷回来仍读不到标记、仍判「还没试过」、于是再刷一次——**停不下来的
   刷新循环加请求风暴**，PWA standalone 下连地址栏都没有，用户只能杀应用。隐私模式的 Safari
   会让 `setItem` 抛 `QuotaExceededError`，不是假想情况。
4. **`onError` 里要 `NProgress.done()`**。导航被中止时 `afterEach` 不执行，进度条会停在 80%
   一直转。「放弃重试」那条分支尤其要有：用户此时留在旧页面上，一个永远转下去的进度条会让他
   以为还在加载，而实际上什么都不会再发生了。
5. **硬刷新对「SW 正在控制」的场景可能无效**，但这不是缺陷：`registerType: 'prompt'` +
   `globPatterns` 覆盖全部 js，旧 chunk 本来就在旧 SW 的 precache 里、根本不会 404。
   真正会撞上这件事的是**没有 SW 的那批用户**——本项目多为局域网 `http://` 自部署，
   浏览器压根不注册 Service Worker，等同一个普通 SPA，而硬刷新对他们是有效的。

## ANTI-PATTERNS
- **一页里有多份能各自提交的数据时，保存完只重载刚保存的那一份**（重命名规则设置页的分类规则分电影 / 剧集两张表、各有一个保存按钮）。旧的 `saveRules` 成功后调 `loadRules()` 把两份一起重新拉回来，于是「先改剧集、再改电影、点保存电影」会用服务端数据把剧集那边未保存的编辑**静默覆盖**——不报错、不提示，用户以为两边都存上了。配套的两条：**多个提交按钮不能共用一个 `saving` 布尔量**（点一个两个一起转圈，看起来像两边都在提交，现在存的是「正在保存哪一侧」）；**防抖函数里被取消的那次调用要把它的 Promise 放行**，否则 `await doPreview()` 的调用方永远等不到，`loading` 卡在 true 且毫无迹象。`useRenameConfig.spec.ts` 钉住这三条。
- **页面内的 tab 选中项同步到 URL 用 `composables/useTabQuery`**（`?tab=`，重命名规则设置页先用上）。刷新停在原 tab、能从别处深链。两条别改坏：**用 `router.replace` 不用 push**——切 tab 进了浏览器历史的话，「返回」要连按好几下才离开页面；**只改 query 不会触发 `onBeforeRouteLeave`**（离开的判据是路由记录变了），所以它与同页的「未保存修改」拦截互不干扰，别为了「保险」在切 tab 时再弹一次确认。
- **要吸底 / 吸顶的操作条不能放在 `v-card` 或 `v-window` 里面**：两者都是 `overflow: hidden`，`position: sticky` 会贴在这个不滚动的祖先上，表现为「写了 sticky 但跟着内容一起滚走」，不报错。重命名规则设置页的保存条因此放在卡片**外面**、作为 `.page-container` 的直接子元素（`.rules-save-bar`，`bottom: 12px`），用 `v-if` 跟着当前 tab 显隐；实测 1280×800 下滚到半页，条底距视口底 12px。移动端刻意没做这一条：底部已经有悬浮底栏，再叠一根会互相遮挡，那边仍是每张表后面跟一个整宽保存按钮。
- **单页配置表单（PT 过滤规则 / 洗版规则）的零件已收口，新增同类页面照抄**：分节标题 `SectionDivider`、有序列表 `OrderedList`（维度顺序，上移/下移/可选移除）、优先级列表 `PriorityListField`（有序 + 从可选值里添加，发布组这类开放值用 `allow-custom`）、逗号分隔串多选 `CsvSelect`（历史值保留并提示「命不中」）、吸底保存条 `ConfigSaveBar`（「放弃修改」没改动时置灰；移动端吸在 `--osr-mobile-tabbar-occupied` 之上）；逗号串与顺序的纯函数在 `composables/orderList.ts`。两个 composable 都有未保存拦截（路由离开 + beforeunload），**下拉清空后是 null，提交前要落成空串**——后端 MyBatis-Plus 默认跳过 null 字段，「清空目标分辨率」会保存成功、刷新后值又回来。**`<style scoped>` 没写 `lang="scss"` 时不能用 `&--modifier`**：原生 CSS 嵌套不认，构建只报一条 warning、样式静默不生效。
- **规则类 / 枚举类的多选下拉一律 `v-select`，不要 `v-combobox`**（分类规则的类型 Genre / 原始语言 / 国家地区踩过）。combobox 允许自由输入，用户手打的「动画」「cn-CN」被原样存进逗号分隔串，而后端 `CategoryRule` 是按 TMDb genre id / ISO 码**全等**比对的，这种值永远命不中——不报错、不告警，只表现为「这条规则好像没生效」，是最难从界面上看出来的一类配置错误。换过去不会吃掉存量数据：库里已有的、不在选项表里的历史值 `v-select` 照样保留并原样显示。（参数设置页那条 `returnObject` 是同一个组件的另一个陷阱，见上面「参数设置页」一段。）
- 不要在组件中直接调用 `axios`，统一用 `src/api/` 中的封装
- **没有前端权限 store，别再造一个**。菜单与路由由后端 `getRouters` 按角色过滤后下发，前端
  只负责渲染；能不能调某个接口由后端各 Controller 自己判（见 osr-framework/AGENTS.md「后端的接口鉴权」
  那条）。曾经有个 `stores/permission.ts`：`hasPermission()` 恒返回 `true`、`generateRoutes()`
  基本是空操作，而且**全项目零引用**——它唯一的作用是让人以为前端有一套权限系统，
  照着它加校验会得到一个恒真的判断。已删除。真要做前端级的按钮粒度控制，先想清楚它挡的是
  误操作还是攻击者：挡后者的话唯一有效的位置在后端。
- 不要在组件中写大量业务逻辑，抽到 composables/
- 移动端页面不要使用 PC 端组件 (Vuetify PC 组件)
- 每个页面都要考虑H5端的适配
- 列表页不要各自实现分页/搜索逻辑，复用 `useTaskList`/`useRecordList`
- **打开编辑弹窗的数据按「行内 → 当前页 → 回查列表」三级取，不要一上来就查接口**
  （`useTaskList#handleUpdate`）。绝大多数调用方是卡片/表格行上的「修改」按钮
  （`handleUpdate(item, '修改索引器')`），**整行数据本来就在手里**。旧实现在这里仍去查一次
  `pageNum: 1, pageSize: 100` 的列表再 `.find()`，除了白发一个请求，用户在第 2 页、或数据
  超过 100 条时 `find` 必然落空、弹一句「任务不存在」——而那条数据明明就在屏幕上。工具栏的
  「修改」按钮传的是 `undefined`（id 取自 `selectedIds`），先在当前页数据里找即可；只有
  勾选之后又翻了页（`usePageSelection` 的选择集跨页累加）才会走到回查那一级。
  `form.value = { ...task }` 的浅拷贝**不能省**：直接把 row 赋给 form，用户在弹窗里改一半
  不提交也会顺手改掉列表里那一行。
- **允许小数的 `type="number"` 必须写 `step`，体积字段的 GB↔字节换算只有 `composables/sizeUnits.ts` 一份**。
  不写 step 时浏览器默认 step=1，输入 1.5 会被判成非法值、整个表单提交不了——PT 过滤规则的
  体积下限/上限/偏好体积踩过一次，而报错只是输入框下方一句「请输入有效值」，很容易读成前端坏了。
  换算侧同样不能取整：`Math.round(bytes / GB)` 会把 500MB 这类阈值显示成 **0**，
  而 0 在过滤规则里的语义是「不限」——用户一保存，阈值不是变粗了，是静默失效了。
  step 的粒度（`0.01`）与 `bytesToGb` 的小数位必须一致，否则回填的值不满足 step 约束，
  浏览器会把一个刚刚存进去的合法值标成非法。提交侧必须 `Math.round` 到整数字节：后端字段是 Long。
- **时间格式化只有两份，按「说什么」分工，不要在页面里再写第三份**：
  `composables/relativeTime.ts` 说「多久以前」，`composables/dateTime.ts` 说「几点几分」
  （`formatDateTime`）与「跑了多久」（`formatDuration`）。定时任务页与它的执行记录弹窗
  原先各自私有一份，而**只有一份记得处理 Safari**：`new Date('2026-09-20 03:00:00')` 在
  iOS Safari 上是 Invalid Date，必须先把中间那个空格换成 `T`。漏掉这条不报错、不告警——
  时间在手机上原样显示成后端那串、耗时变成 `-`，而这是个装到手机上用的 PWA。
  后端 Jackson 按 `yyyy-MM-dd HH:mm:ss` + GMT+8 序列化（`application.yml`），
  两份实现都按这个格式假设输入。`dateTime.spec.ts` 里那条 Safari 用例的判据刻意用
  **不带年份**的形态：带年份时「真的解析了」与「解析失败原样返回」的输出恰好一模一样，
  分不出来，等于一条永远通过的空检查。
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

## NOTES
- **「逐集跑一遍」这类跑批必须在开始前把订阅对象快照下来，不能在循环里现读 `currentSubscription`**。订阅进度弹窗的「一键补齐全部」每集要等一次几十秒的检索（后端单索引器预算 30 秒还是软上限），几十集就是十几二十分钟；这期间弹窗点遮罩就能关，用户去点开另一条订阅的进度会把 `currentSubscription` 换掉——旧实现每轮现读它，于是剩下的集变成「拿 A 的集号、按 B 的标题、推给 B 的订阅」，**界面上没有任何迹象**（loading 挂在已经关掉的弹窗上）。同理收尾回写进度也要判 `currentSubscription?.id === 快照.id`，否则会把用户正在看的另一条订阅的弹窗内容改掉。跑批期间弹窗设 `persistent`、显示「已完成 N/M」并给一个中止入口（置个 flag，当前这一集跑完就停，不打断已发出的请求）。`composables/__tests__/usePtSubscription.spec.ts` 里三条用例钉住这些。
- **PT 统计仪表盘的取数与图表选项只有 `composables/usePtStats.ts` 一份，PC 与移动端共用**。两端各写一份的代价已经兑现过：「搜索淘汰原因分布」只有 PC 有、移动端从来没加；失败原因的配色函数被复制了两遍，于是同一个 bug 存在两份——那个函数是从首页状态卡抄来的、按"名字里带不带『失败』"猜颜色，而失败原因文案一个都命中不了，`findIndex` 恒返回 -1，**整张饼图恒为同一个红色**。三条：**配色按分类码查表且走 `--osr-*` 令牌**（写死的十六进制在暗色主题下不跟着变，正是 `plugins/echartsTheme.ts` 当初要修的毛病）；**主题切换只 bump `themeTick` 让 option 重算，绝不重新取数**（旧实现的 `osr-theme-change` 处理器直接调取数函数，切一次深浅色就打一轮扫全表的聚合查询）；**ECharts 实例一律经 `composables/useEchart.ts` 绑定**——旧实现四张图各写一遍 init/resize/dispose，而 `onUnmounted` 里漏掉了其中一张，每进出一次页面泄漏一个实例，不报错不告警，这类"N 份样板里少写一份"只能靠收口消掉。另：平均耗时后端一直在算也一直在传，两端都没画，现在挂在趋势图第二根 Y 轴上。
- **PT 菜单分四组，新增菜单挂到语义对应的那一组**（20260785）：`PT 追剧`(2070) 追剧日历/缺集体检/订阅管理/热门自动订阅、`PT 下载`(2079) 下载记录/统计仪表盘/转移做种、`PT 规则`(2080) 过滤规则/洗版规则/黑名单、`PT 接入`(2081) 索引器/下载器/媒体服务器。四个分组都直接挂在 `parent_id=0`，**不要再引入第三级**——20260752 刚把三级收敛成两级，桌面端 `SidebarMenuItem.vue` 也是把分组渲染成一行标题、子项平铺，再套一层只会多出一行没人点的标题。分组标题已带 PT，**子菜单名不要再写 `PT` 前缀**（侧边栏宽 220px）。改分组归属只 `UPDATE parent_id/order_num/menu_name`，`menu_id`/`url`/`perms` 不动，`sys_role_menu` 按 `menu_id` 关联因此不受影响；但**新建 M 分组要把旧分组的角色授权继承过去**（`INSERT IGNORE INTO sys_role_menu SELECT role_id, <新id> FROM sys_role_menu WHERE menu_id=<旧id>`）——非管理员走 `selectMenusByUserId`，父级 M 菜单没授权的话整组子菜单都不显示，而管理员走 `selectMenuNormalAll` 看不出问题。
- **图标是 lucide，`sys_menu.icon` 直接写 lucide 官方名**（kebab-case，如 `bell-ring`、`calendar-days`，全站描边风格），**中间没有翻译层**。前端由 `plugins/lucideIcons.ts` 注册一个 Vuetify 自定义 IconSet，图标按需引入；模板里写的、库里存的、lucide 官网上叫的，是同一个名字。三条不要改坏的：**（1）新图标必须在 `lucideIcons.ts` 的 `icons` 表里登记**——名字从数据库来，打包时静态分析看不见，漏登记的表现是那个菜单显示一个问号（开发模式下另有 console.warn）；**（2）Vuetify 的 63 个 `$` 别名要一并给全**（下拉箭头、勾选框、排序箭头、分页），漏掉的那个别名在对应组件上表现为「图标位置空着」，不报错不告警，而 v-select / v-checkbox / v-data-table 遍布全站；**（3）绝不要再引入 mdi→lucide 的运行时字典。** 这条路走过四次：历史上 icon 存的是 Font Awesome 类名（RuoYi 遗留），而前端是 Vuetify、**根本没引入 Font Awesome**，只能靠 `useMenuIcon.ts` 里一张手写字典翻译——于是建菜单要改两处，漏了不报错也不告警，只是那个菜单没图标（侧边栏用 `v-if` 包着 `#prepend`，图标认不出时整个插槽不渲染，该项比同级少一块缩进，肉眼极易忽略）。`sql/` 里 4 个 fix-menu-icon 迁移都是这么来的，20260778 的「通知路由」又栽了一次。20260780 换成 mdi 名拆掉了 fa 字典，20260791 换成 lucide 名——两次都是**一次性 codemod + 一条 SQL 迁移，跑完即弃**。
  **品牌图标是唯一的例外**：lucide 官方不收 logo（已剥离到 simple-icons），而 Telegram / 企业微信的图标本身就承担「这条通知走哪个渠道」的识别功能。这两个从 simple-icons 取官方路径内联在 `lucideIcons.ts` 里，名字是 `brand-telegram` / `brand-wecom`，是**全站仅有的两个实心图标**——品牌标识按惯例就是实心的，描边版本认不出来。新增通知渠道时照抄，不要为了统一风格把 logo 改成描边。
  另：`mdi-spin` 那类 MDI 字体自带的修饰类随字体一起没了，加载图标的自转改成 `motion.scss` 里针对 `.lucide-loader-circle` 的规则，**它是全站唯一不走 `--osr-dur-*` 令牌的动画**（令牌在 reduced-motion 下被压到 0.01ms，套上去就是每秒转十万圈）。
- **参数设置页的分组按「配置键前缀」归类**（`SECTION_RULES`，见 `views/system/config/index.vue`），不是按键名里的关键词猜。加同前缀的配置零改动就落到正确分组；全新前缀落进「其他」，看得见但不会错放。旧实现是一串 if-else 匹配子串，41 个配置里有 15 个掉进兜底的「基础配置」——通知类和登录安全类全在里面。分组之上还有一层**标签**（`CONFIG_TABS`，分组靠 `tab` 字段挂上去）：分组回答「配置属于谁」，标签回答「用户到哪儿找」，原先一个分组一个标签，10 个里有几个只有 1~3 项、1280 宽下一行排不下；合并后标签内按分组分小节。新增分组时 `tab` 必须指向已有标签，否则那批配置整个不出现（`configMeta.spec.ts` 守着）。两条别改坏的：**下拉用 `v-select`，不要换回 `v-combobox`**——后者 `returnObject` 默认为 true，选中后配置值变成 `{label,value}` 对象被整个发给后端，不报错；**元数据的说明缺失时退回数据库 `remark`**（`hintOf`），迁移脚本插配置都写了 remark，新配置忘登记元数据也不会是光秃秃一个输入框，但数字/密码类仍要登记控件类型，否则数字框能存进 `abc`、token 编辑时是明文。
- **PC 列表页的表头排序全部接在 `useDataTable#onSortChange` 上**，落成 `orderByColumn`/`isAsc` 两个参数交给 `BaseController#selectPage`。`v-data-table-server` **只发事件、不自己排数据**（它手里本来就只有当前一页），不接这个事件的表现是「点表头、箭头翻转、一行不动」——10 个 PC 列表页此前全是这个状态，只有定时任务页因为用的是客户端的 `v-data-table` 才碰巧能排。三条不要改坏的：**表头 key 不是数据库列的必须标 `sortable: false`**（`detail`/`config`/`fileInfo` 这类把几个字段拼成一格的合成列，传过去就是个不存在的列名，整页 500，而用户只是点了一下表头）；**各 Controller `buildQueryWrapper` 里的默认排序要留着**——MyBatis-Plus 的分页拦截器把 `Page` 上的排序放在 SQL 自带 ORDER BY 之**前**（`PageOrderPrecedenceTest` 钉住了这个第三方行为，反过来的话所有排序都会被 create_time 静默吃掉），默认排序因此降为次级键，同值行的先后仍然稳定，去掉它翻页会出现重复行与漏行；**`resetQueryParams` 不清排序参数**，理由与 pageSize 相同，且箭头是表格自己的状态、清了参数就会和实际顺序对不上。
