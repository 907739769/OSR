<template>
  <div class="mobile-dashboard">
    <div class="welcome-card">
      <div class="welcome-top">
        <div class="welcome-text">
          <h2>欢迎回来, {{ userStore.userInfo?.userName || '管理员' }}</h2>
          <p class="subtitle" title="点击换一句" @click="loadQuote">“{{ quote }}”</p>
        </div>
        <div class="welcome-date">
          <div class="welcome-weekday">{{ weekdayText }}</div>
          <div class="welcome-day">{{ dateText }}</div>
        </div>
      </div>
    </div>

    <!-- 统计概览 -->
    <v-progress-linear v-if="loading && statCards.length" indeterminate color="primary" />
    <v-alert v-if="statError && !loading" type="error" variant="tonal" density="compact">
      统计数据加载失败
      <template #append>
        <v-btn size="small" variant="text" @click="reloadStats">重试</v-btn>
      </template>
    </v-alert>
    <div v-else class="stats-grid">
      <!-- 首屏骨架：尺寸同 .stat-card（图标 36 + 数字 + 标签），数据到了原地换成真卡片 -->
      <template v-if="loading && !statCards.length">
        <div v-for="i in 6" :key="'sk-' + i" class="stat-card osr-sheen osr-skeleton" :style="{ '--osr-i': i - 1 }" aria-hidden="true">
          <span class="osr-bone osr-bone--block stat-skeleton-icon" />
          <span class="osr-bone stat-skeleton-value" />
          <span class="osr-bone osr-bone--caption stat-skeleton-label" />
        </div>
      </template>
      <div
        v-for="(stat, index) in statCards"
        :key="stat.label"
        class="stat-card osr-enter"
        :class="[stat.type, { clickable: !!stat.path }]"
        :style="{ '--osr-i': index }"
        @click="stat.path && router.push(stat.path)"
      >
        <div class="stat-icon">
          <v-icon :icon="stat.icon" />
        </div>
        <div class="stat-value"><AnimatedNumber :value="stat.value" /></div>
        <div class="stat-label">{{ stat.label }}</div>
      </div>
    </div>

    <!-- 待办提醒：与 PC 首页同源（useDashboardTodo），没有待办时不占位 -->
    <div v-if="todoItems.length || todoFailed" class="todo-section">
      <h3>待办提醒</h3>
      <div v-if="!todoItems.length" class="todo-failed">部分数据没取到，暂时无法判断是否有待办</div>
      <div
        v-for="item in todoItems"
        :key="item.key"
        class="todo-row"
        @click="item.path && router.push(item.path)"
      >
        <span class="todo-count" :class="item.tone">{{ item.count }}</span>
        <span class="todo-label">{{ item.label }}</span>
        <span class="todo-hint">{{ item.hint }}</span>
      </div>
    </div>

    <!-- 今日处理数量：按 COPY/STRM/Rename 分类展示，对应 PC 端饼图的统计口径 -->
    <div class="today-section">
      <h3>今日处理</h3>
      <div class="stats-grid">
        <!-- 首屏骨架：尺寸同 .stat-card（图标 36 + 数字 + 标签），数据到了原地换成真卡片 -->
        <template v-if="todayLoading && !todayStatCards.length">
          <div v-for="i in 3" :key="'sk-' + i" class="stat-card osr-sheen osr-skeleton" :style="{ '--osr-i': i - 1 }" aria-hidden="true">
            <span class="osr-bone osr-bone--block stat-skeleton-icon" />
            <span class="osr-bone stat-skeleton-value" />
            <span class="osr-bone osr-bone--caption stat-skeleton-label" />
          </div>
        </template>
        <div
          v-for="(stat, index) in todayStatCards"
          :key="stat.label"
          class="stat-card osr-enter"
          :class="[stat.type, { clickable: !!stat.path }]"
          :style="{ '--osr-i': index }"
          @click="stat.path && router.push(stat.path)"
        >
          <div class="stat-icon">
            <v-icon :icon="stat.icon" />
          </div>
          <div class="stat-value"><AnimatedNumber :value="stat.value" /></div>
          <div class="stat-label">{{ stat.label }}</div>
        </div>
      </div>
    </div>

    <!-- 快捷入口：直接取当前用户的菜单，避免写死路径造成死链 -->
    <div class="quick-actions" v-if="quickLinks.length">
      <h3>快捷操作</h3>
      <div class="action-grid">
        <div
          v-for="link in quickLinks"
          :key="link.path"
          class="action-item"
          @click="router.push(link.path)"
        >
          <v-icon :icon="link.icon" />
          <span>{{ link.title }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getDashboardStatsApi, getCopyStatsApi, getStrmStatsApi, getRenameStatsApi } from '@/api/openlist/dashboard'
import { useDashboardHeader } from '@/composables/useDashboardHeader'
import { useDashboardTodo } from '@/composables/useDashboardTodo'
import { useMenuLinks } from '@/composables/useMenuLinks'
import { getRoutePathForComponent } from '@/router'
import AnimatedNumber from '@/components/AnimatedNumber.vue'

interface StatCard {
  label: string
  value: number | string
  icon: string
  type: 'primary' | 'success' | 'warning' | 'info'
  /** 有值时卡片可点击跳转 */
  path?: string
}

const router = useRouter()
const userStore = useUserStore()
const loading = ref(true)
const statError = ref(false)
const { items: todoItems, failed: todoFailed, load: loadTodo } = useDashboardTodo()
const todayLoading = ref(true)
const { weekdayText, dateText, quote, refreshDate, loadQuote } = useDashboardHeader('MobileDashboard')

const statCards = ref<StatCard[]>([])
const todayStatCards = ref<StatCard[]>([])

/** 通过 meta.componentKey 反查动态路由，避免写死 /openliststrm 前缀路径（后端菜单两种前缀并存，写死会 404） */
const recordPaths = {
  copy: getRoutePathForComponent('openlist/copyRecord/index') || '/openliststrm/copy',
  strm: getRoutePathForComponent('openlist/strmRecord/index') || '/openliststrm/strm',
  renameDetail: getRoutePathForComponent('openlist/renameDetail/index') || '/openliststrm/renameDetail'
}

function buildStatCards(data: any): StatCard[] {
  return [
    { label: '同步记录', value: data?.copyRecordCount ?? 0, icon: 'files', type: 'primary', path: recordPaths.copy },
    { label: 'STRM 记录', value: data?.strmRecordCount ?? 0, icon: 'video', type: 'success', path: recordPaths.strm },
    { label: '重命名明细', value: data?.renameDetailCount ?? 0, icon: 'square-pen', type: 'warning', path: recordPaths.renameDetail },
    { label: '成功率', value: data?.successRate != null ? data.successRate + '%' : '--', icon: 'circle-check', type: 'info' },
    { label: '失败数', value: data?.failedCount ?? 0, icon: 'circle-x', type: 'warning' },
    { label: '处理中', value: data?.processingCount ?? 0, icon: 'loader-circle', type: 'primary' }
  ]
}

/** 接口按状态分组返回 { 成功: n, 失败: n, ... }，今日处理数量取各状态之和 */
function sumStatusCounts(data: Record<string, number> | null | undefined): number {
  if (!data) return 0
  return Object.values(data).reduce((sum, n) => sum + (Number(n) || 0), 0)
}

function buildTodayStatCards(copy: number, strm: number, rename: number): StatCard[] {
  return [
    { label: '今日同步', value: copy, icon: 'files', type: 'primary', path: recordPaths.copy },
    { label: '今日STRM', value: strm, icon: 'video', type: 'success', path: recordPaths.strm },
    { label: '今日重命名', value: rename, icon: 'square-pen', type: 'warning', path: recordPaths.renameDetail }
  ]
}

const quickLinks = useMenuLinks()

async function reloadStats() {
  loading.value = true
  try {
    const data: any = await getDashboardStatsApi()
    statCards.value = buildStatCards(data)
    statError.value = false
  } catch (e) {
    // 失败就明说，不再拿一排 0 冒充「系统很干净」
    console.error('[MobileDashboard] 统计数据加载失败:', e)
    statError.value = true
  } finally {
    loading.value = false
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') refreshDate()
}

onMounted(async () => {
  loadQuote()
  loadTodo()
  document.addEventListener('visibilitychange', onVisibilityChange)

  await reloadStats()

  try {
    const [copyToday, strmToday, renameToday] = await Promise.all([
      getCopyStatsApi('today'),
      getStrmStatsApi('today'),
      getRenameStatsApi('today')
    ])
    todayStatCards.value = buildTodayStatCards(
      sumStatusCounts(copyToday as any),
      sumStatusCounts(strmToday as any),
      sumStatusCounts(renameToday as any)
    )
  } catch (e) {
    console.error('[MobileDashboard] 今日统计加载失败:', e)
    todayStatCards.value = buildTodayStatCards(0, 0, 0)
  } finally {
    todayLoading.value = false
  }
})
onUnmounted(() => document.removeEventListener('visibilitychange', onVisibilityChange))
</script>

<style scoped lang="scss">
.mobile-dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.welcome-card {
  background: linear-gradient(135deg, var(--osr-primary), var(--osr-primary-hover));
  color: var(--osr-on-primary, #fff);
  padding: 20px 16px;
  border-radius: var(--osr-radius-lg);

  .welcome-top {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 12px;

    .welcome-text {
      flex: 1;
      min-width: 0;
    }
  }

  h2 {
    margin: 0 0 4px;
    font-size: 18px;
  }

  .subtitle {
    margin: 0;
    opacity: 0.9;
    font-size: 13px;
    cursor: pointer;
  }

  .welcome-date {
    flex-shrink: 0;
    text-align: center;

    .welcome-weekday {
      font-size: 14px;
      font-weight: 600;
      line-height: 1.3;
    }

    .welcome-day {
      font-size: 11px;
      opacity: 0.85;
      margin-top: 1px;
    }
  }
}

/* ============================================
   统计卡片
   ============================================ */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  min-height: 80px;

  .stat-card {
    background: var(--osr-surface);
    border-radius: var(--osr-radius-md);
    padding: 14px 8px;
    display: flex;
    flex-direction: column;
    align-items: center;
    box-shadow: var(--osr-shadow-base);
    transition: transform var(--osr-transition-fast);

    &.clickable {
      cursor: pointer;

      &:active {
        transform: scale(0.97);
      }
    }

    .stat-icon {
      width: 36px;
      height: 36px;
      border-radius: var(--osr-radius-base);
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 8px;

      .v-icon {
        font-size: 18px;
      }
    }

    .stat-value {
      font-size: 18px;
      font-weight: 700;
      color: var(--osr-text-primary);
      line-height: 1.2;
    }

    /* 骨架：与上面的图标 / 数字 / 标签逐项同尺寸 */
    .stat-skeleton-icon {
      width: 36px;
      height: 36px;
      margin-bottom: 8px;
      border-radius: var(--osr-radius-base);
    }

    .stat-skeleton-value {
      width: 40px;
      height: 20px;
    }

    .stat-skeleton-label {
      width: 48px;
      margin-top: 5px;
    }

    .stat-label {
      font-size: 11px;
      color: var(--osr-text-secondary);
      margin-top: 2px;
      text-align: center;
    }

    &.primary .stat-icon {
      background: linear-gradient(135deg, var(--osr-primary-muted), var(--osr-primary-subtle));
      color: var(--osr-primary);
    }
    &.success .stat-icon {
      background: linear-gradient(135deg, var(--osr-success-light), var(--osr-bg-page));
      color: var(--osr-success);
    }
    &.warning .stat-icon {
      background: linear-gradient(135deg, var(--osr-warning-light), var(--osr-bg-page));
      color: var(--osr-warning);
    }
    &.info .stat-icon {
      background: linear-gradient(135deg, var(--osr-info-light), var(--osr-bg-page));
      color: var(--osr-info);
    }
  }
}

/* ============================================
   今日处理
   ============================================ */
.todo-section {
  h3 {
    font-size: 15px;
    font-weight: 600;
    margin-bottom: 8px;
  }

  .todo-failed {
    font-size: 13px;
    color: var(--osr-text-secondary);
  }

  .todo-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 12px;
    margin-bottom: 6px;
    border-radius: var(--osr-radius-md);
    background: var(--osr-surface);
    box-shadow: var(--osr-shadow-base);
    cursor: pointer;
  }

  .todo-count {
    min-width: 28px;
    text-align: center;
    font-weight: 700;
    padding: 1px 6px;
    border-radius: var(--osr-radius-md);

    &.warning {
      color: var(--osr-warning);
      background: var(--osr-warning-light);
    }
    &.error {
      color: var(--osr-error);
      background: var(--osr-error-light);
    }
  }

  .todo-label {
    font-size: 14px;
    color: var(--osr-text-primary);
  }

  .todo-hint {
    margin-left: auto;
    font-size: 12px;
    color: var(--osr-text-secondary);
  }
}

.today-section {
  h3 {
    font-size: 15px;
    color: var(--osr-text-primary);
    margin: 0 0 12px;
  }
}

/* ============================================
   快捷操作
   ============================================ */
.quick-actions {
  h3 {
    font-size: 15px;
    color: var(--osr-text-primary);
    margin: 0 0 12px;
  }

  .action-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 8px;
    background: var(--osr-surface);
    padding: 12px 8px;
    border-radius: var(--osr-radius-lg);
    box-shadow: var(--osr-shadow-base);

    .action-item {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 4px;
      /* 触控目标不低于 44px */
      min-height: 60px;
      padding: 8px 4px;
      border-radius: var(--osr-radius-base);
      cursor: pointer;
      transition: background var(--osr-transition-fast);

      &:active {
        background: var(--osr-bg-page);
      }

      .v-icon {
        font-size: 22px;
        color: var(--osr-primary);
      }

      span {
        font-size: 11px;
        color: var(--osr-text-secondary);
        text-align: center;
        line-height: 1.3;
        overflow: hidden;
        text-overflow: ellipsis;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
      }
    }
  }
}
</style>
