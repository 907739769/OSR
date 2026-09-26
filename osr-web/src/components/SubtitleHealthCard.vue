<template>
  <!-- 字幕体检：PC 与移动端体检页共用。数据由 usePtHealth 管，这里只负责展示 -->
  <v-card class="table-card subtitle-card">
    <div class="subtitle-head">
      <div>
        <div class="subtitle-title">
          <v-icon icon="captions" size="18" />
          <span>字幕体检</span>
        </div>
        <p class="subtitle-desc">
          已入库的集里，媒体服务器上查不到中文字幕流的（内封与外挂都算）。有中文音轨的集和华语作品不参与。
          <b>压在画面里的硬字幕查不到，会被报出来</b>（常见于网络平台的动画 WEB-DL）。补完字幕、刷新媒体库后再查一次即可确认。
        </p>
      </div>
      <v-btn variant="tonal" color="primary" size="small" :loading="loading" @click="emit('load')">
        {{ loaded ? '重新检查' : '检查' }}
      </v-btn>
    </div>

    <template v-if="loaded && !loading && report">
      <v-alert v-if="report.unsupported" type="info" variant="tonal" density="compact" class="mb-2">
        没有可用来查字幕的媒体服务器。目前只支持 Emby / Jellyfin，请在「媒体服务器」里配置并启用。
      </v-alert>
      <v-alert v-for="text in report.failures" :key="text" type="warning" variant="tonal" density="compact" class="mb-2">
        {{ text }}
      </v-alert>
      <template v-if="!report.unsupported">
        <p class="subtitle-summary">
          已检查 {{ report.checkedSubscriptions }} 部 / {{ report.checkedEpisodes }} 集，{{ report.issues.length }} 部有问题
        </p>
        <v-empty-state
          v-if="!report.issues.length"
          icon="badge-check"
          title="没有发现缺中文字幕的集"
          text="已入库的外语作品在媒体服务器上都有中文字幕或中文音轨"
        />
        <div v-for="item in report.issues" :key="item.subId" class="subtitle-row">
          <div class="subtitle-row-head">
            <a class="subtitle-name" @click="emit('open', item.subId)">
              {{ item.title }}<template v-if="item.year"> ({{ item.year }})</template><template v-if="item.season"> S{{ String(item.season).padStart(2, '0') }}</template>
            </a>
          </div>
          <div v-if="item.noChinese.length" class="subtitle-line">
            <span class="subtitle-label warn">没有中文字幕</span>
            <span>{{ describe(item, item.noChinese) }}</span>
          </div>
          <div v-if="item.unknownLanguage.length" class="subtitle-line">
            <span class="subtitle-label">有字幕、语言未知</span>
            <span>{{ describe(item, item.unknownLanguage) }}</span>
          </div>
          <div v-if="item.noMediaInfo.length" class="subtitle-line">
            <span class="subtitle-label">媒体服务器暂无媒体信息</span>
            <span>{{ describe(item, item.noMediaInfo) }}</span>
          </div>
        </div>
      </template>
    </template>
  </v-card>
</template>

<script setup lang="ts">
import type { SubtitleIssue, SubtitleReport } from '@/api/openlist/ptHealth'

defineProps<{
  report: SubtitleReport | null
  loading: boolean
  /** 点过「检查」才有数；没点过时不显示空状态，免得读成「一切正常」 */
  loaded: boolean
}>()

const emit = defineEmits<{
  load: []
  open: [subId: number]
}>()

/** 电影只有一集（0），不写集号 */
function describe(item: SubtitleIssue, episodes: number[]) {
  if (item.mediaType === 'MOVIE') return '正片'
  return `第 ${episodes.join('、')} 集`
}
</script>

<style scoped lang="scss">
.subtitle-card {
  padding: 16px 20px;
  margin-top: 16px;
}

.subtitle-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.subtitle-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--osr-text-primary);
}

.subtitle-desc {
  margin: 4px 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--osr-text-secondary);
}

.subtitle-summary {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.subtitle-row {
  padding: 10px 0;
  border-top: 1px solid var(--osr-border-light);
}

.subtitle-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.subtitle-name {
  font-weight: 600;
  cursor: pointer;
  color: rgb(var(--v-theme-primary));
}

.subtitle-line {
  display: flex;
  gap: 8px;
  margin-top: 4px;
  font-size: 13px;
  flex-wrap: wrap;
}

.subtitle-label {
  flex: none;
  color: var(--osr-text-secondary);

  &.warn {
    color: rgb(var(--v-theme-warning));
  }
}
</style>
