<template>
  <div class="page-container">
    <PageHeader
      icon="calendar-days"
      title="追剧日历"
      desc="按播出日期排布已订阅剧集的每一集，颜色即该集当前状态；暂停的订阅不入历"
    >
      <template #actions>
        <v-btn variant="outlined" prepend-icon="calendar-1" @click="goToday">回到本月</v-btn>
      </template>
    </PageHeader>

    <v-card class="table-card">
      <div class="calendar-toolbar">
        <div class="month-nav">
          <v-btn icon="chevron-left" variant="text" density="comfortable" @click="goPrevMonth" />
          <span class="month-label">{{ monthLabel }}</span>
          <v-btn icon="chevron-right" variant="text" density="comfortable" @click="goNextMonth" />
          <!-- 只能一格格翻的话，看三个月前要点三次、看去年要点十二次 -->
          <v-text-field
            :model-value="anchorMonth"
            type="month"
            density="compact"
            variant="outlined"
            hide-details
            class="month-picker"
            @update:model-value="goMonth"
          />
        </div>
        <!-- 图例本来就是这套颜色的说明，顺手让它可点：日历上绝大多数是已入库的绿色，
             不筛的话真正要找的缺失/阻塞会被淹掉 -->
        <div class="legend">
          <button
            type="button"
            class="legend-item"
            :class="{ 'legend-item--active': activeState === '' }"
            @click="setState('')"
          >全部 {{ entries.length }}</button>
          <button
            v-for="s in LEGEND"
            :key="s.key"
            type="button"
            class="legend-item"
            :class="{ 'legend-item--active': activeState === s.key }"
            @click="setState(s.key)"
          >
            <i class="legend-dot" :class="`legend-dot--${s.key.toLowerCase()}`" />{{ s.label }}
            <span class="legend-count">{{ stateCounts[s.key] || 0 }}</span>
          </button>
        </div>
      </div>

      <v-progress-linear v-if="loading" indeterminate color="primary" />

      <!-- 加载失败要单独说：塞回空结果的话渲染出来是「本月没有排播」，
           而用户对「某个月没排播」本来就没有预期，只会当成真的 -->
      <v-empty-state
        v-if="!loading && loadFailed"
        icon="circle-alert"
        color="error"
        title="日历加载失败"
        text="没能拿到排播数据，下面的空白不代表这个月没有更新。"
      >
        <template #actions>
          <v-btn color="primary" variant="flat" prepend-icon="refresh-cw" @click="load">重试</v-btn>
        </template>
      </v-empty-state>

      <template v-else>
        <div class="calendar-grid">
          <div v-for="label in WEEKDAY_LABELS" :key="label" class="weekday-cell">{{ label }}</div>

          <template v-for="(week, wi) in weeks" :key="wi">
            <div
              v-for="cell in week"
              :key="cell.key"
              class="day-cell"
              :class="{ 'day-cell--muted': !cell.inMonth, 'day-cell--today': cell.isToday }"
            >
              <div class="day-number">{{ cell.day }}</div>
              <div class="day-entries">
                <button
                  v-for="entry in previewOf(cell.key)"
                  :key="`${entry.subId}-${entry.episode}`"
                  type="button"
                  class="entry"
                  :class="`entry--${entry.state.toLowerCase()}`"
                  :title="`${entry.title} S${pad(entry.season)}E${pad(entry.episode)} · ${stateMeta(entry.state).label}`"
                  @click="openEntry(entry)"
                >
                  <span class="entry-ep">E{{ pad(entry.episode) }}</span>
                  <span class="entry-title">{{ entry.title }}</span>
                </button>
                <!-- 原先超出的部分在格子里内部滚动：看不出还有几集，鼠标划过日历还会误触 -->
                <button
                  v-if="hiddenCountOf(cell.key) > 0"
                  type="button"
                  class="day-more"
                  @click="openDay(cell.key)"
                >
                  +{{ hiddenCountOf(cell.key) }} 集
                </button>
              </div>
            </div>
          </template>
        </div>

        <div v-if="!loading && !hasEntriesInMonth" class="calendar-empty">
          <v-empty-state
            icon="calendar"
            :title="activeState ? '本月没有该状态的排播' : '本月没有排播'"
            :text="activeState
              ? '换一个状态筛选看看，或点「全部」'
              : '只有剧集订阅会出现在这里；播出日期由 TMDb 同步，新订阅可能需要等一轮同步任务'"
          />
        </div>
      </template>
    </v-card>

    <!-- 当日全部排播 -->
    <v-dialog v-model="dayDialogOpen" max-width="480">
      <v-card :title="dayDialogTitle">
        <v-card-text>
          <div v-for="entry in dayDialogEntries" :key="`${entry.subId}-${entry.episode}`" class="day-row">
            <i class="legend-dot" :class="`legend-dot--${entry.state.toLowerCase()}`" />
            <span class="day-row-title">{{ entry.title }}</span>
            <span class="day-row-ep">S{{ pad(entry.season) }}E{{ pad(entry.episode) }}</span>
            <v-chip size="x-small" :color="stateColor(entry.state)" variant="tonal">
              {{ stateMeta(entry.state).label }}
            </v-chip>
            <v-btn variant="text" size="small" @click="openSubscription(entry)">查看订阅</v-btn>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="dayDialogOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 单集详情：点一集问的多半是「这一集怎么了」，直接跳走会丢掉日历上下文 -->
    <v-dialog v-model="entryDialogOpen" max-width="420">
      <v-card v-if="activeEntry" title="这一集">
        <v-card-text>
          <p class="entry-dialog-title">{{ activeEntry.title }}</p>
          <div class="entry-dialog-row">
            <span class="label">集号</span>
            <span class="value">S{{ pad(activeEntry.season) }}E{{ pad(activeEntry.episode) }}</span>
          </div>
          <div class="entry-dialog-row">
            <span class="label">播出日期</span>
            <span class="value">{{ activeEntry.airDate }}</span>
          </div>
          <div class="entry-dialog-row">
            <span class="label">状态</span>
            <v-chip size="small" :color="stateColor(activeEntry.state)" variant="tonal">
              {{ stateMeta(activeEntry.state).label }}
            </v-chip>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="entryDialogOpen = false">关闭</v-btn>
          <v-btn color="primary" variant="flat" @click="openSubscription(activeEntry)">查看订阅</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import { useRouter } from 'vue-router'
import { usePtCalendar, stateMeta, WEEKDAY_LABELS } from '@/composables/usePtCalendar'
import { getRoutePathForComponent } from '@/router'
import type { CalendarEntry } from '@/api/openlist/ptCalendar'

const {
  loading, loadFailed, entries, anchor, monthLabel, weeks, entriesByDate,
  activeState, stateCounts, setState, hasEntriesInMonth,
  load, goPrevMonth, goNextMonth, goToday, goMonth
} = usePtCalendar()

const LEGEND = [
  { key: 'IN_LIBRARY', label: '已入库' },
  { key: 'IN_FLIGHT', label: '下载中' },
  { key: 'UPGRADING', label: '洗版中' },
  { key: 'BLOCKED', label: '已阻塞' },
  { key: 'MISSING', label: '缺失' }
]

const pad = (n: number) => String(n ?? 0).padStart(2, '0')

/** 月份选择器的值（yyyy-MM），与 anchor 同步 */
const anchorMonth = computed(() => anchor.value.format('YYYY-MM'))

/**
 * 一格里最多平铺 4 条，其余收进「+N 集」。
 * 原先是给格子设 max-height 让内部滚动：一天排播多时多出来的只能靠在 100px 的小框里
 * 滚动才发现，鼠标划过日历还会误触。
 */
const DAY_PREVIEW_LIMIT = 4
const entriesOf = (key: string) => entriesByDate.value[key] || []
const previewOf = (key: string) => entriesOf(key).slice(0, DAY_PREVIEW_LIMIT)
const hiddenCountOf = (key: string) => Math.max(0, entriesOf(key).length - DAY_PREVIEW_LIMIT)

/** 当日全部排播 */
const dayDialogOpen = ref(false)
const dayDialogKey = ref('')
const dayDialogEntries = computed(() => entriesOf(dayDialogKey.value))
const dayDialogTitle = computed(() => `${dayDialogKey.value} 的排播（${dayDialogEntries.value.length} 集）`)
const openDay = (key: string) => {
  dayDialogKey.value = key
  dayDialogOpen.value = true
}

/** 单集详情 */
const entryDialogOpen = ref(false)
const activeEntry = ref<CalendarEntry | null>(null)
const openEntry = (entry: CalendarEntry) => {
  activeEntry.value = entry
  entryDialogOpen.value = true
}

/** 状态色。stateMeta 给的 default 不是 Vuetify 的合法色名，转成 undefined 让 chip 用默认色 */
const stateColor = (state: string) => {
  const color = stateMeta(state).color
  return color === 'default' ? undefined : color
}

const router = useRouter()
/**
 * 跳到订阅页并直接展开它的进度弹窗。
 * query 用 id 而不是 subId —— 订阅页两端读的都是 route.query.id（见其 onMounted）。
 * 路径不写死：后端菜单 path 历史上有 /openlist 与 /openliststrm 两种前缀，写死会跳 404。
 */
const openSubscription = (entry: CalendarEntry) => {
  const path = getRoutePathForComponent('openlist/ptSubscription/index')
  if (path) router.push({ path, query: { id: String(entry.subId) } })
}
</script>

<style scoped>
.calendar-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  flex-wrap: wrap;
}

.month-nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.month-label {
  min-width: 130px;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
  color: var(--osr-text-primary);
}

.legend {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  font-size: 12px;
  color: var(--osr-text-secondary);
  cursor: pointer;
}

.legend-item:hover {
  border-color: var(--osr-border-base);
}

.legend-item--active {
  border-color: var(--osr-primary-accent);
  background: var(--osr-primary-subtle);
  color: var(--osr-text-primary);
}

.legend-count {
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-disabled);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  background: var(--osr-border-dark);
}

.legend-dot--in_library { background: rgb(var(--v-theme-success)); }
.legend-dot--in_flight { background: rgb(var(--v-theme-info)); }
.legend-dot--upgrading { background: rgb(var(--v-theme-warning)); }
.legend-dot--blocked { background: rgb(var(--v-theme-error)); }

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 1px;
  background: var(--osr-border-light);
  border-top: 1px solid var(--osr-border-light);
}

.weekday-cell {
  padding: 8px 0;
  text-align: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--osr-text-secondary);
  background: var(--osr-bg-page);
}

.day-cell {
  min-height: 104px;
  /* 不再需要 max-height + 内部滚动：一格最多平铺 4 条，其余收进「+N 集」弹窗，
     行高自然有界，而且看得出还有多少 */
  padding: 6px;
  background: rgb(var(--v-theme-surface));
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.day-cell--muted {
  background: var(--osr-bg-page);

  .day-number { opacity: 0.4; }
}

.day-cell--today {
  box-shadow: inset 0 0 0 2px var(--osr-primary-accent);

  .day-number {
    color: rgb(var(--v-theme-primary));
    font-weight: 700;
  }
}

.day-number {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.day-entries {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.day-more {
  align-self: flex-start;
  padding: 1px 5px;
  border: none;
  background: transparent;
  font-size: 11px;
  color: var(--osr-primary);
  cursor: pointer;
}

.entry {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  padding: 2px 5px;
  border: none;
  border-radius: 4px;
  /* 缺失态用中性色，其余状态由 .entry--* 覆盖。令牌是 --osr-border-dark，
     写成不存在的 --osr-border 会回退到 currentColor，缺失态渲染成近黑 */
  border-left: 3px solid var(--osr-border-dark);
  background: var(--osr-bg-page);
  font-size: 11px;
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
}

.entry:hover {
  background: var(--osr-primary-subtle);
}

.entry--in_library { border-left-color: rgb(var(--v-theme-success)); }
.entry--in_flight { border-left-color: rgb(var(--v-theme-info)); }
.entry--upgrading { border-left-color: rgb(var(--v-theme-warning)); }
.entry--blocked { border-left-color: rgb(var(--v-theme-error)); }

.entry-ep {
  flex: none;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.entry-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--osr-text-primary);
}

.calendar-empty {
  padding: 24px 0;
}

.month-picker {
  width: 150px;
  margin-left: 8px;
}

/* 当日排播弹窗 */
.day-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--osr-border-light);
}

.day-row-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--osr-text-primary);
}

.day-row-ep {
  flex: none;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

/* 单集详情弹窗 */
.entry-dialog-title {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
}

.entry-dialog-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  font-size: 13px;
}

.entry-dialog-row .label {
  width: 72px;
  flex: none;
  color: var(--osr-text-secondary);
}

.entry-dialog-row .value {
  color: var(--osr-text-primary);
}

@media (max-width: 768px) {
  .page-container {
    padding: 0;
  }
}
</style>
