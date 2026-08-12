<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-calendar-month-outline"
      title="追剧日历"
      desc="按播出日期排布已订阅剧集的每一集，颜色即该集当前状态"
    >
      <template #actions>
        <v-btn variant="outlined" prepend-icon="mdi-calendar-today" @click="goToday">回到本月</v-btn>
      </template>
    </PageHeader>

    <v-card class="table-card">
      <div class="calendar-toolbar">
        <div class="month-nav">
          <v-btn icon="mdi-chevron-left" variant="text" density="comfortable" @click="goPrevMonth" />
          <span class="month-label">{{ monthLabel }}</span>
          <v-btn icon="mdi-chevron-right" variant="text" density="comfortable" @click="goNextMonth" />
        </div>
        <div class="legend">
          <span v-for="s in LEGEND" :key="s.key" class="legend-item">
            <i class="legend-dot" :class="`legend-dot--${s.key.toLowerCase()}`" />{{ s.label }}
          </span>
        </div>
      </div>

      <v-progress-linear v-if="loading" indeterminate color="primary" />

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
                v-for="entry in entriesByDate[cell.key] || []"
                :key="`${entry.subId}-${entry.episode}`"
                type="button"
                class="entry"
                :class="`entry--${entry.state.toLowerCase()}`"
                :title="`${entry.title} S${pad(entry.season)}E${pad(entry.episode)} · ${stateMeta(entry.state).label}`"
                @click="openSubscription(entry)"
              >
                <span class="entry-ep">E{{ pad(entry.episode) }}</span>
                <span class="entry-title">{{ entry.title }}</span>
              </button>
            </div>
          </div>
        </template>
      </div>

      <div v-if="!loading && entries.length === 0" class="calendar-empty">
        <v-empty-state
          icon="mdi-calendar-blank-outline"
          title="本月没有排播"
          text="只有剧集订阅会出现在这里；播出日期由 TMDb 同步，新订阅可能需要等一轮同步任务"
        />
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import { useRouter } from 'vue-router'
import { usePtCalendar, stateMeta, WEEKDAY_LABELS } from '@/composables/usePtCalendar'
import { getRoutePathForComponent } from '@/router'
import type { CalendarEntry } from '@/api/openlist/ptCalendar'

const {
  loading, entries, monthLabel, weeks, entriesByDate,
  goPrevMonth, goNextMonth, goToday
} = usePtCalendar()

const LEGEND = [
  { key: 'IN_LIBRARY', label: '已入库' },
  { key: 'IN_FLIGHT', label: '下载中' },
  { key: 'UPGRADING', label: '洗版中' },
  { key: 'BLOCKED', label: '已阻塞' },
  { key: 'MISSING', label: '缺失' }
]

const pad = (n: number) => String(n ?? 0).padStart(2, '0')

const router = useRouter()
/**
 * 点一集跳到订阅页并直接展开它的进度弹窗。
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
  /* 上限必须给：网格行高取该行最高的格子，一天 11 集就能把整行撑到 400px 开外，
     一屏放不下两行。超出的部分在 .day-entries 内部滚动 */
  max-height: 156px;
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
  /* 一天排播很多时格子内部滚动，不把整行撑高 */
  overflow-y: auto;
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

@media (max-width: 768px) {
  .page-container {
    padding: 0;
  }
}
</style>
