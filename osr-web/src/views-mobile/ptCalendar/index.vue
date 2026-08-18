<template>
  <div class="mobile-page">
    <div class="calendar-bar">
      <v-btn icon="mdi-chevron-left" variant="text" density="comfortable" @click="goPrevMonth" />
      <span class="month-label">{{ monthLabel }}</span>
      <v-btn icon="mdi-chevron-right" variant="text" density="comfortable" @click="goNextMonth" />
      <v-btn variant="text" size="small" class="today-btn" @click="handleGoToday">本月</v-btn>
    </div>

    <!-- 图例即筛选：日历上绝大多数是已入库的绿色，不筛的话真正要找的缺失/阻塞会被淹掉 -->
    <div class="state-tabs">
      <v-chip
        :variant="activeState === '' ? 'flat' : 'outlined'"
        :color="activeState === '' ? 'primary' : undefined"
        size="small"
        @click="setState('')"
      >
        全部 {{ entries.length }}
      </v-chip>
      <v-chip
        v-for="s in LEGEND"
        :key="s.key"
        :variant="activeState === s.key ? 'flat' : 'outlined'"
        :color="stateColor(s.key)"
        size="small"
        @click="setState(s.key)"
      >
        {{ s.label }} {{ stateCounts[s.key] || 0 }}
      </v-chip>
    </div>

    <v-progress-linear v-if="loading" indeterminate color="primary" />

    <!-- 加载失败要单独说：塞回空结果的话渲染出来是「本月没有排播」 -->
    <v-empty-state
      v-if="!loading && loadFailed"
      icon="mdi-alert-circle-outline"
      color="error"
      title="日历加载失败"
      text="没能拿到排播数据，空白不代表这个月没有更新。"
    >
      <template #actions>
        <v-btn color="primary" variant="flat" prepend-icon="mdi-refresh" @click="load">重试</v-btn>
      </template>
    </v-empty-state>

    <div v-else class="task-list">
      <div v-for="day in agenda" :key="day.key" :ref="(el) => registerDay(day.key, el)" class="agenda-day">
        <div class="agenda-date" :class="{ 'agenda-date--today': day.isToday }">
          {{ day.label }}
          <span v-if="day.isToday" class="today-tag">今天</span>
          <span class="agenda-count">{{ day.items.length }} 集</span>
        </div>

        <v-card
          v-for="entry in day.items"
          :key="`${entry.subId}-${entry.episode}`"
          class="task-card"
          @click="openEntry(entry)"
        >
          <div class="card-content">
            <div class="card-top">
              <div class="card-title-row">
                <v-img
                  v-if="entry.posterPath"
                  :src="posterUrl(entry.posterPath)"
                  width="32"
                  height="46"
                  cover
                  class="poster"
                />
                <v-icon v-else class="card-title-icon" icon="mdi-television-classic" size="18" />
                <span class="card-title">{{ entry.title }}</span>
              </div>
              <v-chip size="x-small" :color="stateColor(entry.state)" variant="tonal">
                {{ stateMeta(entry.state).label }}
              </v-chip>
            </div>
            <div class="card-detail">
              <div class="detail-row">
                <span class="label">集号</span>
                <span class="value">S{{ pad(entry.season) }}E{{ pad(entry.episode) }}</span>
              </div>
            </div>
          </div>
        </v-card>
      </div>

      <v-empty-state
        v-if="!loading && agenda.length === 0"
        icon="mdi-calendar-blank-outline"
        :title="activeState ? '本月没有该状态的排播' : '本月没有排播'"
        :text="activeState ? '换一个状态看看' : '只有剧集订阅会出现在这里；播出日期由 TMDb 同步'"
      />
    </div>

    <!-- 单集详情：点一集问的多半是「这一集怎么了」，直接跳走会丢掉日历上下文 -->
    <v-dialog v-model="entryDialogOpen" width="92%">
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
import { nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { usePtCalendar, stateMeta, posterUrl } from '@/composables/usePtCalendar'
import { getRoutePathForComponent } from '@/router'
import type { CalendarEntry } from '@/api/openlist/ptCalendar'

const {
  loading, loadFailed, entries, monthLabel, agenda, today,
  activeState, stateCounts, setState,
  load, goPrevMonth, goNextMonth, goToday
} = usePtCalendar()

const LEGEND = [
  { key: 'IN_LIBRARY', label: '已入库' },
  { key: 'IN_FLIGHT', label: '下载中' },
  { key: 'UPGRADING', label: '洗版中' },
  { key: 'BLOCKED', label: '已阻塞' },
  { key: 'MISSING', label: '缺失' }
]

/** stateMeta 给的 default 不是 Vuetify 的合法色名，转成 undefined 让 chip 用默认色 */
const stateColor = (state: string) => {
  const color = stateMeta(state).color
  return color === 'default' ? undefined : color
}

/**
 * 打开/切回本月时把「今天」滚到视口顶部。
 * 手机上整月清单能有上百张卡片，落在 1 号意味着用户每次都要自己划到今天；
 * 而 PC 是月历网格，一屏就能看到全月，不需要这个。
 */
const dayEls = new Map<string, HTMLElement>()
const registerDay = (key: string, el: any) => {
  if (el) dayEls.set(key, el as HTMLElement)
  else dayEls.delete(key)
}

const scrollToToday = async () => {
  await nextTick()
  dayEls.get(today.value)?.scrollIntoView({ block: 'start' })
}

const scrolledFor = ref('')
watch(agenda, async (list) => {
  if (!list.some((d) => d.key === today.value) || scrolledFor.value === today.value) return
  await scrollToToday()
  scrolledFor.value = today.value
}, { immediate: true })

// 翻走再翻回来要能重新定位
watch(monthLabel, () => { scrolledFor.value = '' })

/**
 * 「本月」按钮。已经在本月、只是往下划走了的情况下，monthLabel 不变、上面那个 watch
 * 不会触发，按钮看起来毫无反应——而那恰恰是它最常见的用法，所以这里直接滚一次。
 */
const handleGoToday = async () => {
  goToday()
  scrolledFor.value = today.value
  await scrollToToday()
}

const pad = (n: number) => String(n ?? 0).padStart(2, '0')

/** 单集详情 */
const entryDialogOpen = ref(false)
const activeEntry = ref<CalendarEntry | null>(null)
const openEntry = (entry: CalendarEntry) => {
  activeEntry.value = entry
  entryDialogOpen.value = true
}

const router = useRouter()
// query 用 id：订阅页读的是 route.query.id。路径不写死，理由同 PC 端
const openSubscription = (entry: CalendarEntry) => {
  const path = getRoutePathForComponent('openlist/ptSubscription/index')
  if (path) router.push({ path, query: { id: String(entry.subId) } })
}
</script>

<style scoped>
.calendar-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  background: rgb(var(--v-theme-surface));
  border-radius: 12px;
  margin-bottom: 10px;
}

.month-label {
  flex: 1;
  text-align: center;
  font-size: 15px;
  font-weight: 600;
  color: var(--osr-text-primary);
}

.today-btn {
  flex: none;
}

.agenda-day {
  margin-bottom: 14px;
  /* 自动定位到今天时给固定顶栏(50px)留出空间，否则日期标题正好被它盖住，
     用户看到的是当天第二张卡片，读不出"这是今天" */
  scroll-margin-top: 64px;
}

.agenda-date {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 2px 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--osr-text-secondary);
}

.agenda-date--today {
  color: rgb(var(--v-theme-primary));
}

.today-tag {
  padding: 1px 6px;
  border-radius: 8px;
  background: var(--osr-primary-subtle);
  color: rgb(var(--v-theme-primary));
  font-size: 11px;
}

.agenda-count {
  margin-left: auto;
  font-weight: 400;
  font-size: 12px;
}

.poster {
  flex: none;
  border-radius: 4px;
}
</style>
