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
          「找中字版本」会从 PT 站给这几集重新下一个标题或描述里带中字标识的版本（走洗版通道，下完自动换上），<b>旧版本不会自动删除</b>。
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
            <!-- 一次只处理一部：每集都要打一轮全站搜索，其余按钮在处理时禁用 -->
            <v-btn
              v-if="item.noChinese.length"
              variant="text"
              color="primary"
              size="small"
              prepend-icon="captions"
              :loading="upgradingId === item.subId"
              :disabled="upgradingId != null && upgradingId !== item.subId"
              @click="emit('upgrade', item)"
            >
              找中字版本
            </v-btn>
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
          <!-- 找中字版本的结果按状态分组：同一个原因只写一次，集号列在后面 -->
          <div v-for="group in upgradeGroups(item)" :key="group.key" class="subtitle-line">
            <span class="subtitle-label" :class="group.tone">{{ group.label }}</span>
            <span>{{ group.text }}</span>
          </div>
        </div>
      </template>
    </template>
  </v-card>
</template>

<script setup lang="ts">
import type { SubtitleIssue, SubtitleReport, SubtitleUpgradeResult, SubtitleUpgradeEpisode } from '@/api/openlist/ptHealth'

const props = withDefaults(defineProps<{
  report: SubtitleReport | null
  loading: boolean
  /** 点过「检查」才有数；没点过时不显示空状态，免得读成「一切正常」 */
  loaded: boolean
  /** 正在找中字版本的订阅 */
  upgradingId?: number | null
  /** 各订阅最近一次找中字版本的结果 */
  upgradeResults?: Record<number, SubtitleUpgradeResult>
}>(), {
  upgradingId: null,
  upgradeResults: () => ({})
})

const emit = defineEmits<{
  load: []
  open: [subId: number]
  upgrade: [item: SubtitleIssue]
}>()

const UPGRADE_LABELS: Record<SubtitleUpgradeEpisode['status'], { label: string; tone: string }> = {
  PUSHED: { label: '已推送中字版', tone: 'ok' },
  NO_CHINESE_RELEASE: { label: '站上没有中字版', tone: 'warn' },
  ALL_FILTERED: { label: '被过滤规则挡掉', tone: 'warn' },
  NOT_FOUND: { label: '没搜到', tone: 'warn' },
  NOT_IN_LIBRARY: { label: '暂不能换', tone: '' },
  FAILED: { label: '推送失败', tone: 'warn' },
  SKIPPED: { label: '未处理', tone: '' }
}

/**
 * 按「状态 + 原因」分组：一季 6 集都没有中字版时只写一行原因，后面跟集号；
 * 已推送的每集种子标题不同，只列集号（标题进日志与下载记录）
 */
function upgradeGroups(item: SubtitleIssue) {
  const result = props.upgradeResults[item.subId]
  if (!result) return []
  const groups = new Map<string, { key: string; label: string; tone: string; episodes: number[]; detail: string }>()
  for (const r of result.results) {
    const detail = r.status === 'PUSHED' ? '' : r.detail
    const key = `${r.status}|${detail}`
    const g = groups.get(key) ?? { key, ...UPGRADE_LABELS[r.status], episodes: [], detail }
    g.episodes.push(r.episode)
    groups.set(key, g)
  }
  return [...groups.values()].map(g => ({
    key: g.key,
    label: g.label,
    tone: g.tone,
    text: describe(item, g.episodes) + (g.detail ? `：${g.detail}` : '')
  }))
}

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

  &.ok {
    color: rgb(var(--v-theme-success));
  }
}
</style>
