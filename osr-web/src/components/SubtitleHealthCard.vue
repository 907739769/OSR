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
          已入库的集里，下载它的种子没识别到中文字幕的。依据是种子标题/描述里的中字标识和种子内的外挂字幕文件名，
          <b>内封中字但标题没写的种子会被误报</b>；华语作品不参与。
        </p>
      </div>
      <v-btn variant="tonal" color="primary" size="small" :loading="loading" @click="emit('load')">
        {{ loaded ? '重新检查' : '检查' }}
      </v-btn>
    </div>

    <template v-if="loaded && !loading">
      <v-empty-state
        v-if="!issues.length"
        icon="badge-check"
        title="没有发现缺中文字幕的集"
        text="经 OSR 下载入库的外语作品都识别到了中文字幕"
      />
      <div v-for="item in issues" :key="item.subId" class="subtitle-row">
        <div class="subtitle-row-head">
          <a class="subtitle-name" @click="emit('open', item.subId)">
            {{ item.title }}<template v-if="item.year"> ({{ item.year }})</template><template v-if="item.season"> S{{ String(item.season).padStart(2, '0') }}</template>
          </a>
          <v-chip v-if="item.titleOnly" size="x-small" variant="outlined">部分仅按标题判断</v-chip>
        </div>
        <div v-if="item.noChinese.length" class="subtitle-line">
          <span class="subtitle-label warn">未识别到中文字幕</span>
          <span>{{ describe(item, item.noChinese) }}</span>
        </div>
        <div v-if="item.unknownLanguage.length" class="subtitle-line">
          <span class="subtitle-label">有字幕、语言未知</span>
          <span>{{ describe(item, item.unknownLanguage) }}</span>
        </div>
      </div>
    </template>
  </v-card>
</template>

<script setup lang="ts">
import type { SubtitleIssue } from '@/api/openlist/ptHealth'

defineProps<{
  issues: SubtitleIssue[]
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
