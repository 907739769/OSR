<template>
  <!-- 进度 -->
  <!-- 补齐跑批期间锁住弹窗：关掉它循环也不会停 -->
  <v-dialog v-model="progressOpen" width="92%" :persistent="searchAllMissingLoading">
    <v-card title="订阅进度">
      <v-card-text>
        <v-progress-linear v-if="progressLoading" indeterminate color="primary" class="mb-3" />
        <template v-if="progress">
          <p class="progress-title">
            {{ progress.title }}
            <!-- 不带季号的话，同一部剧的两条订阅弹出来的进度长得一模一样 -->
            <span v-if="seasonLabel(currentSubscription)" class="progress-season">
              {{ seasonLabel(currentSubscription) }}
            </span>
          </p>
          <v-progress-linear
            :model-value="progress.totalEpisodes ? Math.round((progress.inLibraryCount / progress.totalEpisodes) * 100) : 0"
            color="primary"
            height="8"
            rounded
            class="mb-2"
          />
          <p>已入库 <strong>{{ progress.inLibraryCount }}</strong> / {{ progress.totalEpisodes }} 集</p>
          <p v-if="progress.inFlightCount">在途 {{ progress.inFlightCount }} 集（已推送下载器，尚未入库）</p>
          <div v-if="progress.missingEpisodes && progress.missingEpisodes.length" class="missing-list">
            <span class="missing-lead">
              仍缺 {{ progress.missingEpisodes.length }} 集<span
                v-if="unairedMissingEpisodes.length"
                class="missing-unaired"
              >（{{ unairedMissingEpisodes.length }} 集未播出）</span>：
            </span>
            <!-- 集号本身就是搜索入口。原先每个集号后面挂一个「搜」按钮，
                 一季上百集时等于在弹窗里铺上百个按钮组件 -->
            <button
              v-for="ep in visibleMissingEpisodes"
              :key="ep"
              type="button"
              class="missing-item"
              :class="{ 'missing-item--clickable': currentSubscription && currentSubscription.mediaType !== 'MOVIE' }"
              :disabled="!currentSubscription || currentSubscription.mediaType === 'MOVIE'"
              @click="openEpisodeSearch(currentSubscription, ep)"
            >{{ ep }}</button>
            <button v-if="missingHiddenCount > 0" type="button" class="missing-more" @click="expandMissing">
              还有 {{ missingHiddenCount }} 集，全部展开
            </button>
          </div>
          <p v-else class="all-done">全部集已入库</p>

          <div class="episode-detail-toggle" @click="loadEpisodeDetail">
            {{ episodeDetailOpen ? '收起全部集' : '查看全部集' }}
            <v-icon icon="mdi-chevron-down" :class="{ 'is-open': episodeDetailOpen }" size="16" />
          </div>
          <div v-if="episodeDetailOpen" class="episode-detail-list">
            <v-progress-linear v-if="episodeDetailLoading" indeterminate color="primary" />
            <div v-for="ep in episodeDetail" :key="ep.episode" class="episode-detail-row">
              <span class="ep-num">第{{ ep.episode }}集</span>
              <StatusChip :type="episodeStateColor(ep.state)" :text="episodeStateLabel(ep.state)" />
              <!-- 未播出的集恒为「缺失」，标出来能省掉一整轮「为什么搜不到」的排查 -->
              <span v-if="episodeAirDate(ep)" class="ep-date">
                {{ episodeAirDate(ep) }}
                <span v-if="episodeUnaired(ep)" class="ep-unaired">未播出</span>
              </span>
              <span v-if="qualityLabel(ep)" class="ep-quality" :title="upgradeStateHint(ep)">
                {{ qualityLabel(ep) }}
              </span>
              <v-btn
                v-if="ep.state === 'IN_LIBRARY' || ep.state === 'BLOCKED'"
                variant="text"
                color="warning"
                size="small"
                :loading="resettingEpisode === ep.episode"
                @click="handleResetEpisode(ep)"
              >重置</v-btn>
            </div>
            <v-empty-state v-if="!episodeDetailLoading && episodeDetail.length === 0" icon="mdi-inbox-outline" title="暂无数据" />
          </div>
        </template>
      </v-card-text>
      <v-card-actions class="progress-actions">
        <template v-if="searchAllMissingLoading">
          <!-- 跑批期间只留进度与中止：每集要等一次几十秒的检索，一个光转圈的按钮说不清还要多久 -->
          <span class="batch-search-progress">
            补齐中 {{ searchAllMissingDone }}/{{ searchAllMissingTotal }}
          </span>
          <v-btn variant="text" color="error" size="small" :disabled="searchAllMissingAborted" @click="abortSearchAllMissing">
            {{ searchAllMissingAborted ? '正在停止…' : '停止' }}
          </v-btn>
        </template>
        <template v-else>
          <v-btn v-if="currentSubscription" color="primary" size="small" @click="openSeasonSearch(currentSubscription)">
            搜索补齐
          </v-btn>
          <!-- 计数用「可补齐」而不是「仍缺」：未播出的集不会进跑批，按钮上写 12 却只跑 3 集
               会让用户以为漏跑了 -->
          <v-btn
            v-if="currentSubscription && fillableMissingEpisodes.length > 1"
            color="success"
            size="small"
            @click="handleSearchAllMissing"
          >
            一键补齐（{{ fillableMissingEpisodes.length }}集）
          </v-btn>
        </template>
        <v-spacer />
        <v-btn variant="outlined" size="small" :disabled="searchAllMissingLoading" @click="progressOpen = false">关闭</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

const {
  abortSearchAllMissing,
  currentSubscription,
  episodeAirDate,
  episodeDetail,
  episodeDetailLoading,
  episodeDetailOpen,
  episodeStateColor,
  episodeStateLabel,
  episodeUnaired,
  expandMissing,
  fillableMissingEpisodes,
  handleResetEpisode,
  handleSearchAllMissing,
  loadEpisodeDetail,
  missingHiddenCount,
  openEpisodeSearch,
  openSeasonSearch,
  progress,
  progressLoading,
  progressOpen,
  qualityLabel,
  resettingEpisode,
  searchAllMissingAborted,
  searchAllMissingDone,
  searchAllMissingLoading,
  searchAllMissingTotal,
  seasonLabel,
  unairedMissingEpisodes,
  upgradeStateHint,
  visibleMissingEpisodes
} = usePtSubscriptionContext()
</script>

<style scoped lang="scss">
/* ---- 进度弹窗：每集明细 ---- */
.episode-detail-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--osr-border-light);
  font-size: 13px;
  color: var(--osr-primary);
  cursor: pointer;

  .v-icon {
    transition: transform var(--osr-transition-fast);

    &.is-open {
      transform: rotate(180deg);
    }
  }
}
.episode-detail-list {
  margin-top: 8px;
  max-height: 44vh;
  overflow-y: auto;
}
.episode-detail-row {
  display: flex;
  align-items: center;
  /* 移动端宽度有限，多出播出日期后允许换行，否则日期会把质量摘要挤没 */
  flex-wrap: wrap;
  gap: 6px 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--osr-border-light);

  .ep-date {
    flex-shrink: 0;
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    color: var(--osr-text-secondary);
  }

  .ep-unaired {
    margin-left: 4px;
    color: var(--osr-text-disabled);
  }

  .ep-quality {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.ep-num {
    width: 60px;
    flex-shrink: 0;
    color: var(--osr-text-primary);
  }
}
.progress-actions {
  flex-wrap: wrap;
  gap: 6px;
}
.progress-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}
.progress-season {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--osr-text-secondary);
}
.all-done {
  color: var(--osr-success);
}
/* 缺集串：集号本身就是搜索入口，不再每个集号后面挂一个按钮组件 */
.missing-list {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin: 8px 0;
  font-size: 13px;
}
.missing-lead {
  font-size: 12px;
  color: var(--osr-text-secondary);
}
.missing-unaired {
  color: var(--osr-text-disabled);
}
.missing-item {
  min-width: 30px;
  padding: 2px 6px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-sm);
  background: transparent;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-primary);
  cursor: default;

  &.missing-item--clickable {
    cursor: pointer;
  }
}
.missing-more {
  padding: 2px 6px;
  border: none;
  background: transparent;
  font-size: 12px;
  color: var(--osr-primary);
  cursor: pointer;
}
/* 补齐跑批时的「3/26」 */
.batch-search-progress {
  padding: 0 8px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}
</style>
