<template>
  <div
    class="item-card item-card--compact sub-card osr-enter"
    :class="[statusClass, { 'item-card--selectable': selectionMode }]"
    @click="selectionMode && toggleSubSelect(item)"
  >
    <!-- 海报再当一次配色源：同一张图铺满卡片、糊掉、压到很低的不透明度，
         于是每张卡自带一套来自作品本身的底色。用的是已经加载过的那张图，
         不额外发请求；海报缺失/加载失败时这层不渲染，卡片退回纯表面。 -->
    <div
      v-if="item.posterPath && !posterErrorIds.has(item.id)"
      class="sub-backdrop"
      :style="{ backgroundImage: `url(${posterUrl(item.posterPath)})` }"
      aria-hidden="true"
    />
    <v-checkbox-btn
      v-if="selectionMode"
      class="item-card-checkbox card-checkbox"
      :model-value="isSubSelected(item.id)"
      @click.stop="toggleSubSelect(item)"
    />
    <!-- 海报 + 信息横排。item-card 本身是纵向的，横排是订阅卡特有的，包一层私有容器 -->
    <div class="sub-main">
      <div class="sub-poster">
        <img
          v-if="item.posterPath && !posterErrorIds.has(item.id)"
          :src="posterUrl(item.posterPath)"
          :alt="item.title"
          loading="lazy"
          @error="onPosterError(item.id)"
        />
        <div v-else class="sub-poster-placeholder" :class="item.mediaType === 'MOVIE' ? 'placeholder-movie' : 'placeholder-tv'">
          <v-icon :icon="item.mediaType === 'MOVIE' ? 'film' : 'monitor-play'" size="28" />
          <span class="placeholder-text">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
        </div>
      </div>
      <div class="sub-info">
        <div class="card-header card-header--top">
          <span class="card-title card-title--clamp2" :title="item.title">
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
          <!-- 被单独配过过滤规则/下载器的订阅要看得出来，否则只能逐条打开弹窗才知道 -->
          <v-chip
            v-if="hasFilterOverride(item)"
            class="sub-flag"
            size="x-small"
            color="info"
            variant="tonal"
            prepend-icon="funnel"
            title="该订阅有自己的过滤规则覆盖，未覆盖的项仍沿用全局配置"
          >过滤覆盖</v-chip>
        </div>
        <!-- 入库进度：列表接口已带进度计数，不必逐条点开进度弹窗才知道还缺几集 -->
        <div v-if="item.inLibraryCount !== undefined && item.inLibraryCount !== null" class="sub-progress">
          <!-- 有在途集时挂 .osr-progress--active（surface.scss 的流动高光）：
               「卡在 12/26」与「正在下的 12/26」原先长得一模一样，而这个系统里
               长耗时任务遍地，两者分不开是实打实的可用性问题 -->
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
        <!-- 命中/搜索两个时间并作一行小字。各占一整行 label+value 时它们吃掉卡片近三分之一的
             高度，而它们回答的只是「最近有没有动静」——完整时间戳挂 title -->
        <div class="sub-times" :title="`上次命中 ${item.lastMatchTime || '-'}\n上次搜索 ${item.lastSearchTime || '-'}`">
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
      </div>
    </div>
    <!-- @click.stop：批量模式下卡片本身是可点选的，点操作按钮不该顺手把卡片也选上 -->
    <div class="card-footer" @click.stop>
      <v-btn variant="text" color="primary" size="small" @click="showProgress(item)">进度</v-btn>
      <v-btn variant="text" color="primary" size="small" @click="goDownloadRecords(item)">下载记录</v-btn>
      <v-menu>
        <template #activator="{ props: menuProps }">
          <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="chevron-down">更多</v-btn>
        </template>
        <v-list density="compact">
          <v-list-item v-if="item.status !== 'PAUSED'" @click="handleMoreCommand('pause', item)">暂停</v-list-item>
          <v-list-item v-else @click="handleMoreCommand('resume', item)">恢复</v-list-item>
          <v-list-item @click="handleMoreCommand('search', item)">搜索补齐</v-list-item>
          <v-list-item @click="handleMoreCommand('refresh', item)">对账</v-list-item>
          <v-list-item @click="handleMoreCommand('logs', item)">匹配日志</v-list-item>
          <v-list-item @click="handleMoreCommand('filter', item)">过滤规则</v-list-item>
          <v-divider class="my-1" />
          <!-- 删除置底并单独分隔：它会连带删掉集数追踪记录，与「进度」这类高频动作并排太容易误点 -->
          <v-list-item class="more-actions-danger" @click="handleMoreCommand('remove', item)">删除</v-list-item>
        </v-list>
      </v-menu>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import StatusChip from '@/components/StatusChip.vue'
import { getRoutePathForComponent } from '@/router'
import { useRouter } from 'vue-router'
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

/** 订阅列表里的一张卡。状态全部来自页面 provide 的同一个实例（见 ptSubscriptionContext） */
const props = defineProps<{ item: any }>()

const {
  handlePause,
  handleRefresh,
  handleRemove,
  handleResume,
  isSubSelected,
  openFilterOverride,
  openSeasonSearch,
  posterUrl,
  selectionMode,
  showProgress,
  showSearchLogs,
  toggleAutoSearch,
  toggleSubSelect,
  toggleUpgrade
,
  taskList
} = usePtSubscriptionContext()

const router = useRouter()

/** 海报加载失败的订阅 id 集合，命中则展示占位图标而非裂图；数据刷新后清除以便重试 */
const posterErrorIds = reactive(new Set<number>())
const onPosterError = (id: number) => { posterErrorIds.add(id) }

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
 * 颜色与右上角 StatusChip 一一对应（订阅中=success / 已完成=info / 已暂停=warning），
 * 两处对不上的话，一屏卡片扫过去会读成两套状态体系。
 */
const statusClass = computed(() => {
  if (props.item.status === 'COMPLETED') return 'sub-card--completed'
  if (props.item.status === 'PAUSED') return 'sub-card--paused'
  return 'sub-card--active'
})

/**
 * 「08-20 12:00」。卡片信息列只有 200 多像素，两个完整时间戳（各 19 字符）横排放不下；
 * 而这两个值回答的是「最近有没有动静」，年份与秒都不参与这个判断。完整值挂在 title 上。
 * 认不出格式时原样返回——宁可显示得长一点，也不要静默吃掉一个看不懂的值。
 */
const shortTime = (value?: string) => {
  if (!value) return '-'
  const m = /^\d{4}-(\d{2}-\d{2})[ T](\d{2}:\d{2})/.exec(value)
  return m ? `${m[1]} ${m[2]}` : value
}

const goDownloadRecords = (row: any) => {
  const path = getRoutePathForComponent('openlist/ptDownloadRecord/index')
  if (path) router.push({ path, query: { subId: row.id } })
}

/** "更多"下拉菜单 command → 现有函数的分发，纯路由不新增业务逻辑 */
const handleMoreCommand = (cmd: string, row: any) => {
  switch (cmd) {
    case 'refresh': handleRefresh(row); break
    case 'logs': showSearchLogs(row); break
    case 'filter': openFilterOverride(row); break
    case 'search': openSeasonSearch(row); break
    case 'pause': handlePause(row); break
    case 'resume': handleResume(row); break
    case 'remove': handleRemove(row); break
  }
}

// 列表数据变化（刷新/翻页/重新查询）后清除海报失败集合，海报恢复或 TMDb 修复后能重新加载
watch(taskList, () => posterErrorIds.clear())
</script>

<style scoped lang="scss">
/* 卡片外壳（边框/圆角/深度/hover/可点选/紧凑内距）全部来自 styles/list.scss 的 .item-card，
   这里只写订阅卡特有的：海报背景层、状态标记条、海报横排、进度条、开关行。 */

/* 订阅卡私有外壳。刻意不写成 .item-card { … }——那是 list.scss 的共享类，
   页面里重定义会被 styles/__tests__/design-system.spec.ts 挡住，也确实不该那么写。 */
.sub-card {
  /* 海报背景层要被裁进圆角内 */
  overflow: hidden;

  /* 左侧 3px 状态标记条。用伪元素而不是 border-left：border 会改盒模型，
     把整张卡的内容推右 3px；也不能用 box-shadow inset——.item-card 的 box-shadow
     承载着整套深度令牌，在这里覆写等于把卡片的立体感又抹平回去。 */
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

  /* 暂停态整张卡降一档：海报去色 + 背景层压暗。暂停是「我主动停下的」，
     不是故障，所以是退到背景里而不是标红 */
  &.sub-card--paused {
    .sub-poster img {
      filter: grayscale(0.8);
    }

    .sub-backdrop {
      opacity: calc(var(--osr-poster-veil) * 0.5);
    }
  }
}

/* 底层那张模糊海报。只动 filter/opacity/transform，不触发布局 */
.sub-backdrop {
  position: absolute;
  inset: 0;
  z-index: 0;
  background-size: cover;
  background-position: center 22%;
  filter: blur(28px) saturate(1.5);
  opacity: var(--osr-poster-veil);
  pointer-events: none;
  /* blur 会把图像边缘糊成半透明，放大一点让四条边仍被盖满 */
  transform: scale(1.25);
}

/* 内容压在背景层之上。二者同为 z-index 层叠上下文里的定位元素，
   靠 z-index 显式排序，不依赖 DOM 先后 */
.sub-main,
.card-footer {
  position: relative;
  z-index: 1;
}

.item-card-checkbox {
  position: absolute;
  top: 4px;
  left: 4px;
  z-index: 2;
}

/* 海报 + 信息横排 */
.sub-main {
  display: flex;
  gap: 12px;
  min-width: 0;
}

.sub-poster {
  flex-shrink: 0;
  /* 84×126（2:3）。原先 72×108 时，卡片里视觉分量最重的东西反而是最小的那块，
     整张卡读起来是表格行而不是作品卡 */
  width: 84px;
  height: 126px;
  border-radius: var(--osr-radius-base);
  overflow: hidden;
  background: var(--osr-bg-page);
  /* 海报自己也要与卡片底色分开：它下面就是那张模糊的同图，不描一道边会糊在一起 */
  box-shadow: var(--osr-shadow-base), var(--osr-ring);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
    transition: filter var(--osr-dur-2) var(--osr-ease-out);
  }

  .sub-poster-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    color: var(--osr-text-disabled);
    font-size: 22px;

    /* 下面两组渐变是刻意的装饰色：海报占位在明暗主题下都保持深底白字，
       与主题色无关，因此不走 --osr-* 令牌 */
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
      font-size: 11px;
      font-weight: 500;
      letter-spacing: 1px;
    }
  }
}

.sub-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 命中/搜索并作一行 */
.sub-times {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.sub-time {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;

  .label {
    color: var(--osr-text-placeholder);
  }
}

.sub-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
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
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.sub-progress-inflight {
  color: var(--osr-primary);
}

/* 两个开关并作一行 */
.sub-switches {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.sub-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;

  .label {
    color: var(--osr-text-secondary);
    white-space: nowrap;
  }
}

.sub-year {
  color: var(--osr-text-secondary);
  font-size: 12px;
}
</style>
