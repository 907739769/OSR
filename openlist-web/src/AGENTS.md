# OpenList-web 前端知识库

## OVERVIEW
Vue 3 + Vite + Element Plus + Pinia 前端应用，支持 PWA。包含 PC 端 (`views/`) 和移动端 (`views-mobile/`) 两套界面。

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
├── components/             # 公共组件 (DirectoryTreeSelect, ChangePasswordDialog, mobile/*)
├── composables/            # 组合式函数 (useTaskList, useRecordList, useDebounce 等)
├── layouts/                # 布局组件 (DesktopLayout, MobileLayout)
├── router/                 # 路由配置 (动态路由)
│   └── index.ts
├── stores/                 # Pinia 状态管理
│   ├── app.ts              # 应用全局状态 (设备检测、侧边栏)
│   ├── permission.ts       # 权限/菜单
│   └── user.ts             # 用户状态
├── styles/                 # 全局样式 (CSS 变量主题)
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
- **自动导入**: unplugin-auto-import + unplugin-vue-components，`vue`/`vue-router`/`pinia`/`ElementPlus` 无需手动 import
- **`@` 别名**: 指向 `src/` 目录
- **API 层**: 返回标准 `{ code, msg, data }` 格式，axios 拦截器自动处理
- **路由**: 后端动态返回菜单，前端根据权限生成路由
- **列表页模式**: 使用 composables (`useTaskList`/`useRecordList`) 封装增删改查 + 分页 + 搜索
- **移动端**: `views-mobile/` 独立于 `views/`，使用 `MobileSearchPanel` + `MobilePager` + `FullTextDialog` 组件
- **移动端页面**: 使用 `.modern-dialog` 类名 + `width="90%"` 适配小屏
- **TypeScript**: 严格模式，`vue-tsc` 类型检查
- **CSS 变量**: 使用 `--osr-*` 前缀的设计令牌 (surface, bg-page, text-primary, primary, radius-*, shadow-*, transition-*)

## ANTI-PATTERNS
- 不要在组件中直接调用 `axios`，统一用 `src/api/` 中的封装
- 不要绕过路由守卫，权限校验在 store 中统一处理
- 不要在组件中写大量业务逻辑，抽到 composables/
- 移动端页面不要使用 PC 端组件 (Element Plus PC 组件)
- 每个页面都要考虑H5端的适配
- 列表页不要各自实现分页/搜索逻辑，复用 `useTaskList`/`useRecordList`
