<template>
  <v-card
    class="task-card sub-card"
    :class="[statusClass, { selected: selectionMode && isSubSelected(item.id) }]"
    @click="selectionMode && toggleSubSelect(item)"
  >
    <!-- 与 PC 端同一处理：把已经加载过的那张海报糊掉铺在底层当配色源，不额外发请求 -->
    <div
      v-if="item.posterPath && !posterErrorIds.has(item.id)"
      class="sub-backdrop"
      :style="{ backgroundImage: `url(${posterUrl(item.posterPath)})` }"
      aria-hidden="true"
    />
    <div class="card-checkbox" v-if="selectionMode">
      <v-checkbox-btn
        :model-value="isSubSelected(item.id)"
        density="compact"
        @click.stop="toggleSubSelect(item)"
      />
    </div>
    <div class="sub-poster">
      <img
        v-if="item.posterPath && !posterErrorIds.has(item.id)"
        :src="posterUrl(item.posterPath)"
        :alt="item.title"
        loading="lazy"
        @error="posterErrorIds.add(item.id)"
      />
      <div
        v-else
        class="sub-poster-placeholder"
        :class="item.mediaType === 'MOVIE' ? 'placeholder-movie' : 'placeholder-tv'"
      >
        <v-icon :icon="item.mediaType === 'MOVIE' ? 'film' : 'monitor-play'" size="22" />
        <span class="placeholder-text">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
      </div>
    </div>
    <div class="card-content">
      <div class="card-top">
        <span class="card-title">
          {{ item.title }}
          <span v-if="item.year" class="sub-year">({{ item.year }})</span>
        </span>
        <StatusChip v-if="item.status === 'ACTIVE'" type="success" text="订阅中" />
        <StatusChip v-else-if="item.status === 'COMPLETED'" type="info" text="已完成" />
        <StatusChip v-else type="warning" text="已暂停" />
      </div>
      <div class="sub-meta">
        <span>{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
        <span v-if="item.mediaType !== 'MOVIE'">S{{ item.season }}</span>
        <span>共 {{ item.totalEpisodes }} 集</span>
        <!-- 被单独配过过滤规则的订阅要看得出来，否则只能逐条打开弹窗才知道 -->
        <v-chip
          v-if="hasFilterOverride(item)"
          class="sub-flag"
          size="x-small"
          color="info"
          variant="tonal"
          prepend-icon="funnel"
        >过滤覆盖</v-chip>
      </div>
      <!-- 入库进度：列表接口已带进度计数，不必逐条点开进度弹窗才知道还缺几集 -->
      <div v-if="item.inLibraryCount !== undefined && item.inLibraryCount !== null" class="sub-progress">
        <!-- 有在途集时挂 .osr-progress--active（surface.scss 的流动高光）：
             「卡在 12/26」与「正在下的 12/26」原先长得一模一样 -->
        <v-progress-linear
          :class="{ 'osr-progress--active': item.inFlightCount > 0 }"
          :model-value="progressPercent(item)"
          :color="progressColor(item)"
          height="8"
          rounded
        />
        <span class="sub-progress-text">
          {{ item.inLibraryCount }}/{{ item.totalEpisodes }}
          <span v-if="item.inFlightCount" class="sub-progress-inflight">· 在途 {{ item.inFlightCount }}</span>
        </span>
      </div>
      <!-- 命中/搜索并作一行小字，理由同 PC 端：各占一整行时它们吃掉卡片近三分之一高度 -->
      <div class="sub-times">
        <span class="sub-time"><span class="label">命中</span>{{ shortTime(item.lastMatchTime) }}</span>
        <span class="sub-time"><span class="label">搜索</span>{{ shortTime(item.lastSearchTime) }}</span>
      </div>
      <!-- 两个开关并作一行：各占一行只为两个布尔值，能吃掉卡片近三分之一的高度 -->
      <div class="sub-switches" @click.stop>
        <div class="sub-switch">
          <span class="label">自动补搜</span>
          <v-switch
            :model-value="item.autoSearch"
            true-value="1"
            false-value="0"
            color="primary"
            density="compact"
            hide-details
            @update:model-value="(v: any) => toggleAutoSearch(item, v)"
          />
        </div>
        <div class="sub-switch">
          <span class="label">洗版</span>
          <v-switch
            :model-value="item.upgradeEnabled"
            true-value="1"
            false-value="0"
            color="primary"
            density="compact"
            hide-details
            @update:model-value="(v: any) => toggleUpgrade(item, v)"
          />
        </div>
      </div>
      <div class="card-actions" @click.stop>
        <v-btn variant="text" color="primary" size="small" @click="showProgress(item)">进度</v-btn>
        <v-btn variant="text" color="primary" size="small" @click="goDownloadRecords(item)">下载记录</v-btn>
        <v-btn class="action-more" variant="text" color="default" size="small" icon="ellipsis" @click="emit('more', item)" />
      </div>
    </div>
  </v-card>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import StatusChip from '@/components/StatusChip.vue'
import { getRoutePathForComponent } from '@/router'
import { useRouter } from 'vue-router'
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

/** 订阅列表里的一张卡。状态全部来自页面 provide 的同一个实例（见 ptSubscriptionContext） */
const props = defineProps<{ item: any }>()

/** 「更多」面板挂在页面上（同一时刻只有一个），卡片只负责说「点的是这条」 */
const emit = defineEmits<{ more: [item: any] }>()

const {
  isSubSelected,
  posterUrl,
  selectionMode,
  showProgress,
  toggleAutoSearch,
  toggleSubSelect,
  toggleUpgrade
} = usePtSubscriptionContext()

const router = useRouter()

/** 海报加载失败的订阅 id 集合，命中则展示占位图标而非裂图 */
const posterErrorIds = reactive(new Set<number>())

const goDownloadRecords = (row: any) => {
  const path = getRoutePathForComponent('openlist/ptDownloadRecord/index')
  if (path) router.push({ path, query: { subId: row.id } })
}

/** 该订阅是否配了自己的过滤规则覆盖。空字符串同样算「没有覆盖」，与后端一致 */
const hasFilterOverride = (row: any) => {
  if (!row?.filterOverride) return false
  try {
    return Object.keys(JSON.parse(row.filterOverride) || {}).length > 0
  } catch {
    // 脏数据不该让整个列表炸掉，也不该谎报成「有覆盖」
    return false
  }
}

const progressPercent = (row: any) => {
  const total = Number(row?.totalEpisodes) || 0
  if (!total) return 0
  return Math.round(((Number(row.inLibraryCount) || 0) / total) * 100)
}

/** 满了用成功色，其余用主色——进度条本身不是告警，缺集是常态 */
const progressColor = (row: any) => (progressPercent(row) >= 100 ? 'success' : 'primary')

/**
 * 状态对应的卡片修饰类，驱动左侧那条 3px 标记条与暂停态的海报去色。
 * 颜色与右上角 StatusChip 一一对应（订阅中=success / 已完成=info / 已暂停=warning）。
 */
const statusClass = computed(() => {
  if (props.item.status === 'COMPLETED') return 'sub-card--completed'
  if (props.item.status === 'PAUSED') return 'sub-card--paused'
  return 'sub-card--active'
})

/**
 * 「08-20 12:00」。移动端卡片更窄，两个完整时间戳（各 19 字符）横排放不下；
 * 这两个值回答的是「最近有没有动静」，年份与秒不参与这个判断。
 * 认不出格式时原样返回，不静默吃掉看不懂的值。
 */
const shortTime = (value?: string) => {
  if (!value) return '-'
  const m = /^\d{4}-(\d{2}-\d{2})[ T](\d{2}:\d{2})/.exec(value)
  return m ? `${m[1]} ${m[2]}` : value
}
</script>

<style scoped lang="scss">
.card-title .sub-year {
  font-weight: 400;
  color: var(--osr-text-secondary);
  font-size: 12px;
}

/* 订阅卡私有外壳。刻意不写成 .task-card { … }——那是 mobile-list.scss 的共享类，
   页面里重定义会被 styles/__tests__/design-system.spec.ts 挡住 */
.sub-card {
  overflow: hidden;

  /* 左侧 3px 状态标记条。用伪元素而不是 border-left：后者会改盒模型把内容推右，
     而 .task-card 本身已经用 2px 透明边框表达选中态，再动 border 会打架 */
  &::before {
    content: '';
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    width: 3px;
    z-index: 1;
    background: var(--osr-success);
  }

  &.sub-card--completed::before {
    background: var(--osr-info);
  }

  &.sub-card--paused::before {
    background: var(--osr-warning);
  }

  &.sub-card--paused {
    .sub-poster img {
      filter: grayscale(0.8);
    }

    .sub-backdrop {
      opacity: calc(var(--osr-poster-veil) * 0.5);
    }
  }

  /* 内容压在背景层之上，靠 z-index 显式排序 */
  .card-checkbox,
  .sub-poster,
  .card-content {
    position: relative;
    z-index: 1;
  }
}

/* 底层那张模糊海报。只动 filter/opacity/transform，不触发布局 */
.sub-backdrop {
  position: absolute;
  inset: 0;
  z-index: 0;
  background-size: cover;
  background-position: center 22%;
  filter: blur(24px) saturate(1.5);
  opacity: var(--osr-poster-veil);
  pointer-events: none;
  /* blur 会把图像边缘糊成半透明，放大一点让四条边仍被盖满 */
  transform: scale(1.25);
}

.sub-poster {
  flex-shrink: 0;
  width: 60px;
  height: 90px;
  border-radius: var(--osr-radius-sm);
  overflow: hidden;
  background: var(--osr-bg-page);
  /* 海报下面就是那张模糊的同图，不描一道边会糊在一起 */
  box-shadow: var(--osr-shadow-base), var(--osr-ring);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  .sub-poster-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    color: var(--osr-text-disabled);
    font-size: 20px;

    /* 与 PC 端一致的装饰渐变：明暗主题下都是深底白字，刻意不走 --osr-* 令牌 */
    &.placeholder-movie {
      background:
        radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.14), transparent 45%),
        linear-gradient(135deg, #1e3a5f 0%, #2d5a87 50%, #1e3a5f 100%);
      color: rgba(255, 255, 255, 0.7);
    }

    &.placeholder-tv {
      background:
        radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.14), transparent 45%),
        linear-gradient(135deg, #3b1f47 0%, #6b3a7a 50%, #3b1f47 100%);
      color: rgba(255, 255, 255, 0.7);
    }

    .placeholder-text {
      font-size: 10px;
      font-weight: 500;
      letter-spacing: 1px;
    }
  }
}

.sub-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: var(--osr-text-secondary);
}

.sub-flag {
  /* chip 自带的高度在这一行里偏大，压到与旁边的文字同高 */
  height: 18px;
}

.sub-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sub-progress-text {
  flex-shrink: 0;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.sub-progress-inflight {
  color: var(--osr-primary);
}

/* 命中/搜索并作一行 */
.sub-times {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.sub-time {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;

  .label {
    color: var(--osr-text-placeholder);
  }
}

/* 两个开关并作一行 */
.sub-switches {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}

.sub-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;

  .label {
    color: var(--osr-text-secondary);
    white-space: nowrap;
  }
}
</style>
