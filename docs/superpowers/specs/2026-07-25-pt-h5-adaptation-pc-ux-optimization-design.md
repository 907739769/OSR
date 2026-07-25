# PT页面 H5适配 + PC交互优化 设计方案

> 日期: 2026-07-25 | 状态: 已确认

## 一、背景

之前对 PT 订阅、PT 下载记录做了批量操作和交互优化，但只改了 PC 端页面 (`views/`)，H5 端 (`views-mobile/`) 没有同步适配。PT 统计仪表盘 H5 端完全缺失。PC 端交互也有改进空间。

## 二、改造范围总览

| 模块 | PC端改造 | H5端改造 | 路由改造 |
|------|----------|----------|----------|
| PT订阅 | ✅ 交互优化 | ✅ 批量操作+抽屉+排序 | 无 |
| PT下载记录 | ✅ 交互优化 | ✅ 批量重试 | 无 |
| PT统计仪表盘 | ❌ | ✅ 新建移动端页面 | ✅ createDeviceView |

## 三、H5 端 PT 订阅改造

**文件**: `openlist-web/src/views-mobile/ptSubscription/index.vue`

### 3.1 批量操作

点击卡片切换选中态 → 底部浮现 batch-bar。

```
batch-bar 样式（跟 ptDownloader/ptIndexer 现有 batch-bar 一致）：
┌──────────────────────────────────────┐
│ 已选 3 项  批量暂停  批量恢复  批量删除  取消 │
└──────────────────────────────────────┘
```

- `@click` 在 `selectionMode` 不存在时无效果（非批量模式的点击暂时不响应，避免误触）
- 选中卡片加 `.selected` class：蓝色左边框 + 浅蓝背景
- batch-bar 中删除走 `batchDeletePtSubscriptionApi`，复用 formatBatchResultMessage 提示

### 3.2 操作按钮收纳

卡片底部只保留 2 个核心按钮 + 「···」：

```
[进度] [下载记录] [···]
```

点击「···」弹出 `el-drawer`（方向 `btt`，底部弹出）：

```
┌──────────────────────┐
│     更多操作          │
│ ─────────────────── │
│   暂停 / 恢复        │
│   搜索补齐            │
│   对账               │
│   匹配日志           │
│   过滤规则           │
│   删除               │
│              [关闭]  │
└──────────────────────┘
```

- 暂停/恢复文案根据当前行的 `status` 切换
- `el-drawer` 用 `size="auto"` 让高度自适应内容

### 3.3 排序选择器

搜索面板内增加排序下拉（跟 PC 端 queryParams.sortBy 对齐）：

```
el-select: 默认（最新创建）/ 上次命中时间
```

`usePtSubscription` composable 已有 `sortBy` 字段和 `sortBy` 查询参数，无需改 composable。

## 四、H5 端 PT 下载记录改造

**文件**: `openlist-web/src/views-mobile/ptDownloadRecord/index.vue`

### 4.1 批量重试

- 点击卡片切换选中（**仅 FAILED** 状态的卡片响应点击，非 FAILED 卡片无交互）
- 选中卡片加 `.selected` class + 失败卡片原有的 `.card-fail` 样式叠加
- 底部 batch-bar：

```
已选 N 项  批量重试  取消
```

### 4.2 模板变更

- 卡片根元素加 `@click` 和 `:class`
- 模板底部加 `batch-bar`（`v-if="selectedIds.length > 0"`）
- composable 已全支持，无需改逻辑层

## 五、H5 端 PT 统计仪表盘新建

**文件**: 新建 `openlist-web/src/views-mobile/ptStatsDashboard/index.vue`

### 5.1 布局结构

```
┌─────────────────────────────────┐
│  统计卡片 (横向滚动 flex)         │
│  ┌──────┐ ┌──────┐ ┌──────┐    │
│  │总订阅│ │活跃  │ │记录  │ →  │
│  └──────┘ └──────┘ └──────┘    │
├─────────────────────────────────┤
│ [概览] [趋势] [索引器] [原因] [订阅] │  ← el-tabs
├─────────────────────────────────┤
│     对应 Tab 内容                │
└─────────────────────────────────┘
```

### 5.2 统计卡片

- 横向 `flex` + `overflow-x: auto`，`gap: 10px`
- 每张卡片约 140px 宽：图标在上、数值在中、标签在下
- 底部 `scrollbar` 隐藏（`-webkit-scrollbar: none`）
- 卡片颜色分类跟 PC 端一致（primary/success/warning/info）

### 5.3 Tab 1：概览

- 复用卡片数据 + 时间范围选择器 `el-radio-group`（7/30/90天）+ 刷新按钮
- 紧凑布局，选择器和刷新按钮同行

### 5.4 Tab 2：下载量趋势

- ECharts 折线图，高度 280px
- x 轴标签 `rotate: 45` 防重叠
- tooltip + legend 保留

### 5.5 Tab 3：索引器命中率

- ECharts 堆叠条形图，高度 280px
- y 轴标签超出 10 字符截断加省略号

### 5.6 Tab 4：失败原因分布

- ECharts 环形饼图，高度 260px
- 标签字体缩小到 10px

### 5.7 Tab 5：Top 活跃订阅

- 表格数据改为卡片列表，每张卡片展示：
  - 标题（第一行）
  - 类型/季号 + 下载次数 + 完成数 + 失败数（第二行）
  - 上次命中时间（第三行）

### 5.8 路由

`openlist-web/src/router/index.ts` 中：

```ts
// componentMap 新增：
'openlist/ptStatsDashboard/index': createDeviceView(
  () => import('@/views/openlist/ptStatsDashboard/index.vue'),
  () => import('@/views-mobile/ptStatsDashboard/index.vue')
),
```

### 5.9 API

复用 `@/api/openlist/ptStats` 全部 5 个接口，无需改动。

## 六、PC 端交互优化

### 6.1 批量模式卡片点击

**文件**: `views/openlist/ptSubscription/index.vue` + `views/openlist/ptDownloadRecord/index.vue`

- 批量模式下 `.sub-card` / `.record-card` 加 `@click` 调用 toggle 函数
- 加 `.selectable` class，`cursor: pointer`
- hover 时边框变蓝（`border-color: var(--osr-primary)`）
- checkbox 保留，`@click.stop` 防止冒泡（checkbox 本身已有独立的 click 处理）

### 6.2 操作栏自适应

```scss
.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;  // 窄屏时自动换行
  margin-bottom: 12px;
}
```

- 左侧按钮组和右侧控件在窄屏挤不下时自动换到下一行
- 保持各自内部 `gap: 6px` 分组

### 6.3 排序下拉加标签

订阅页排序下拉前加 `排序：` 文字标签：

```html
<span class="sort-label">排序：</span>
<el-select v-model="queryParams.sortBy" ...>
```

`.sort-label` 字体稍小、颜色用次级文字色，跟下拉框居中对齐。

### 6.4 卡片网格宽度上限

```scss
// 原来
grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
// 改为
grid-template-columns: repeat(auto-fill, minmax(340px, 480px));
```

- 订阅和下载记录两页都改
- 1920px 宽屏从约 5 列变成约 4 列，但每张卡片内容更紧凑

### 6.5 骨架屏数量动态计算

```ts
const skeletonCount = computed(() => {
  if (typeof window === 'undefined') return 6
  const cardMinWidth = 340 + 14 // minmax + gap
  const containerWidth = window.innerWidth - 32 - 32 // padding
  return Math.max(3, Math.min(12, Math.floor(containerWidth / cardMinWidth)))
})
```

两页都加这个 computed，模板里 `v-for="n in skeletonCount"`。但注意响应式：需要监听 resize 或用 `ref` + `onMounted`/`onUnmounted` 更新。简化为用 `ref` + resize listener。

### 6.6 批量工具栏 sticky

```scss
.batch-toolbar {
  position: sticky;
  top: 0;
  z-index: 2;
  // ... 其余样式不变
}
```

两页都加。由于 `.batch-toolbar` 在 `.table-card` 内部，sticky 相对于卡片容器。

## 七、兼容性注意点

- ECharts 在移动端需要 `nextTick` 后初始化，且 `onUnmounted` 必须 dispose 避免内存泄漏（PC 端已有此模式，H5 照抄）
- `el-drawer` 方向 `btt`（bottom-to-top）需要 Element Plus 2.2+ 
- 移动端 `batch-bar` 样式直接复用 `ptDownloader` 的 `.batch-bar` 样式（现有的 `views-mobile/ptDownloader/index.vue` 中定义）
- H5 端不引入新的 composable，全部复用现有 `usePtSubscription` / `usePtDownloadRecord`

## 八、文件变更清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `views-mobile/ptSubscription/index.vue` | 批量操作 + 抽屉 + 排序 |
| 修改 | `views-mobile/ptDownloadRecord/index.vue` | 批量重试 |
| 新建 | `views-mobile/ptStatsDashboard/index.vue` | 移动端仪表盘 |
| 修改 | `router/index.ts` | ptStatsDashboard 加 createDeviceView |
| 修改 | `views/openlist/ptSubscription/index.vue` | 交互优化 6 项 |
| 修改 | `views/openlist/ptDownloadRecord/index.vue` | 交互优化 6 项 |

## 九、测试要点

- PT 订阅 H5：卡片点击选中 → batch-bar 浮现 → 批量暂停/恢复/删除 → 抽屉弹出
- PT 下载记录 H5：仅 FAILED 卡片可选中 → 批量重试
- PT 统计仪表盘 H5：5 个 tab 切换 → 图表加载 → resize 不崩
- PC 订阅/下载记录：批量模式卡片点击选中 → sticky 批量栏 → 响应式断点
- 路由：H5 设备访问 ptStatsDashboard 应加载 H5 版页面
