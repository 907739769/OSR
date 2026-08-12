<template>
  <div class="mobile-page">
    <div class="calendar-bar">
      <v-btn icon="mdi-chevron-left" variant="text" density="comfortable" @click="goPrevMonth" />
      <span class="month-label">{{ monthLabel }}</span>
      <v-btn icon="mdi-chevron-right" variant="text" density="comfortable" @click="goNextMonth" />
      <v-btn variant="text" size="small" class="today-btn" @click="goToday">本月</v-btn>
    </div>

    <v-progress-linear v-if="loading" indeterminate color="primary" />

    <div class="task-list">
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
          @click="openSubscription(entry)"
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
              <v-chip size="x-small" :color="stateMeta(entry.state).color" variant="tonal">
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
        title="本月没有排播"
        text="只有剧集订阅会出现在这里；播出日期由 TMDb 同步"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { usePtCalendar, stateMeta, posterUrl } from '@/composables/usePtCalendar'
import { getRoutePathForComponent } from '@/router'
import type { CalendarEntry } from '@/api/openlist/ptCalendar'

const {
  loading, monthLabel, agenda, todayKey,
  goPrevMonth, goNextMonth, goToday
} = usePtCalendar()

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

const scrolledFor = ref('')
watch(agenda, async (list) => {
  if (!list.some((d) => d.key === todayKey) || scrolledFor.value === todayKey) return
  await nextTick()
  dayEls.get(todayKey)?.scrollIntoView({ block: 'start' })
  scrolledFor.value = todayKey
}, { immediate: true })

// 翻走再翻回来要能重新定位，否则「本月」按钮点了没反应
watch(monthLabel, () => { scrolledFor.value = '' })

const pad = (n: number) => String(n ?? 0).padStart(2, '0')

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
