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
    <v-progress-linear v-if="loading" indeterminate color="primary" />
    <div class="stats-grid">
      <div
        v-for="stat in statCards"
        :key="stat.label"
        class="stat-card"
        :class="[stat.type, { clickable: !!stat.path }]"
        @click="stat.path && router.push(stat.path)"
      >
        <div class="stat-icon">
          <v-icon :icon="stat.icon" />
        </div>
        <div class="stat-value">{{ stat.value }}</div>
        <div class="stat-label">{{ stat.label }}</div>
      </div>
    </div>

    <!-- 今日处理数量：按 COPY/STRM/Rename 分类展示，对应 PC 端饼图的统计口径 -->
    <div class="today-section">
      <h3>今日处理</h3>
      <v-progress-linear v-if="todayLoading" indeterminate color="primary" />
      <div class="stats-grid">
        <div
          v-for="stat in todayStatCards"
          :key="stat.label"
          class="stat-card"
          :class="[stat.type, { clickable: !!stat.path }]"
          @click="stat.path && router.push(stat.path)"
        >
          <div class="stat-icon">
            <v-icon :icon="stat.icon" />
          </div>
          <div class="stat-value">{{ stat.value }}</div>
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
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getDashboardStatsApi, getCopyStatsApi, getStrmStatsApi, getRenameStatsApi } from '@/api/openlist/dashboard'
import { getHitokotoApi } from '@/api/openlist/hitokoto'
import { useMenuLinks } from '@/composables/useMenuLinks'

interface StatCard {
  label: string
  value: number | string
  icon: string
  type: 'primary' | 'success' | 'warning' | 'info'
  /** 有值时卡片可点击跳转 */
  path?: string
}

/** 一言接口请求失败时的备用文案 */
const FALLBACK_QUOTES = [
  '代码写得好，Bug就是少。',
  '生活明朗，万物可爱。',
  '愿你被这个世界温柔以待。',
  '不积跬步，无以至千里。',
  '心之所向，素履以往。'
]

function randomFallbackQuote(): string {
  return FALLBACK_QUOTES[Math.floor(Math.random() * FALLBACK_QUOTES.length)]
}

const router = useRouter()
const userStore = useUserStore()
const loading = ref(true)
const todayLoading = ref(true)
const quote = ref(randomFallbackQuote())
const weekdayText = new Date().toLocaleDateString('zh-CN', { weekday: 'long' })
const dateText = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })

function loadQuote() {
  getHitokotoApi()
    .then((data) => {
      quote.value = data.from ? `${data.hitokoto} —— ${data.from}` : data.hitokoto
    })
    .catch((e) => {
      console.error('[MobileDashboard] 每日一言加载失败:', e)
    })
}

const statCards = ref<StatCard[]>([])
const todayStatCards = ref<StatCard[]>([])

function buildStatCards(data: any): StatCard[] {
  return [
    { label: '同步记录', value: data?.copyRecordCount ?? 0, icon: 'mdi-file-multiple-outline', type: 'primary', path: '/openliststrm/copy' },
    { label: 'STRM 记录', value: data?.strmRecordCount ?? 0, icon: 'mdi-video-outline', type: 'success', path: '/openliststrm/strm' },
    { label: '重命名明细', value: data?.renameDetailCount ?? 0, icon: 'mdi-pencil-outline', type: 'warning', path: '/openliststrm/renameDetail' },
    { label: '成功率', value: data?.successRate > 0 ? data.successRate + '%' : '--', icon: 'mdi-check-circle-outline', type: 'info' },
    { label: '失败数', value: data?.failedCount ?? 0, icon: 'mdi-close-circle-outline', type: 'warning' },
    { label: '处理中', value: data?.processingCount ?? 0, icon: 'mdi-loading mdi-spin', type: 'primary' }
  ]
}

/** 接口按状态分组返回 { 成功: n, 失败: n, ... }，今日处理数量取各状态之和 */
function sumStatusCounts(data: Record<string, number> | null | undefined): number {
  if (!data) return 0
  return Object.values(data).reduce((sum, n) => sum + (Number(n) || 0), 0)
}

function buildTodayStatCards(copy: number, strm: number, rename: number): StatCard[] {
  return [
    { label: '今日同步', value: copy, icon: 'mdi-file-multiple-outline', type: 'primary', path: '/openliststrm/copy' },
    { label: '今日STRM', value: strm, icon: 'mdi-video-outline', type: 'success', path: '/openliststrm/strm' },
    { label: '今日重命名', value: rename, icon: 'mdi-pencil-outline', type: 'warning', path: '/openliststrm/renameDetail' }
  ]
}

const quickLinks = useMenuLinks()

onMounted(async () => {
  loadQuote()

  try {
    const data: any = await getDashboardStatsApi()
    statCards.value = buildStatCards(data)
  } catch (e) {
    console.error('[MobileDashboard] 统计数据加载失败:', e)
    statCards.value = buildStatCards(null)
  } finally {
    loading.value = false
  }

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
