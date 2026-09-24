<template>
  <!-- 一键诊断：PC 与移动端订阅页共用（状态在 usePtSubscription 里） -->
  <v-dialog v-model="diagnosisOpen" max-width="760" width="94vw" scrollable>
    <v-card>
      <v-card-title class="diag-title">
        <v-icon icon="stethoscope" size="20" />
        <span>一键诊断<template v-if="diagnosis">：{{ diagnosis.title }}</template></span>
      </v-card-title>
      <v-card-text>
        <v-progress-linear v-if="diagnosisLoading" indeterminate color="primary" />
        <template v-else-if="diagnosis">
          <v-alert
            v-for="(note, i) in diagnosis.notes"
            :key="i"
            type="warning"
            variant="tonal"
            density="compact"
            class="mb-2"
          >{{ note }}</v-alert>

          <v-empty-state
            v-if="!diagnosis.episodes.length"
            icon="circle-check"
            title="没有要诊断的集"
            text="已播出的集都已入库（未播出的集不参与诊断）"
          />

          <div v-for="ep in diagnosis.episodes" :key="ep.episode" class="diag-ep">
            <div class="diag-ep-head">
              <span class="diag-ep-label">{{ ep.label }}</span>
              <v-chip size="x-small" variant="tonal" :color="stateColor(ep.state)">{{ stateText(ep.state) }}</v-chip>
              <span v-if="ep.candidates" class="diag-ep-meta">{{ ep.candidates }} 个候选 · {{ ep.accepted }} 个通过</span>
            </div>
            <div class="diag-ep-summary">{{ ep.summary }}</div>
            <div v-if="ep.reasons.length" class="diag-reasons">
              <v-chip v-for="r in ep.reasons" :key="r.label" size="x-small" variant="outlined" color="error">
                {{ r.label }} × {{ r.count }}
              </v-chip>
            </div>
          </div>

          <p v-if="diagnosis.pendingTotal > diagnosis.episodes.length" class="diag-more">
            共 {{ diagnosis.pendingTotal }} 集未入库，只列了前 {{ diagnosis.episodes.length }} 集。
          </p>
        </template>
      </v-card-text>
      <v-card-actions>
        <v-btn
          v-if="canSearch"
          color="primary"
          variant="tonal"
          prepend-icon="search"
          :loading="searching"
          @click="searchNow"
        >立即补搜</v-btn>
        <v-spacer />
        <v-btn variant="outlined" @click="diagnosisOpen = false">关闭</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'
import { searchMissingApi } from '@/api/openlist/ptHealth'
import { message } from '@/composables/useMessage'

const { diagnosisOpen, diagnosisLoading, diagnosis, diagnosisTarget, showDiagnosis } = usePtSubscriptionContext()

const searching = ref(false)

/** 与后端「补搜只对订阅中的生效」一致；没有缺集时也不给，按了只会得到一句「没有可搜索的缺集」 */
const canSearch = computed(() =>
  diagnosisTarget.value?.status === 'ACTIVE' && (diagnosis.value?.episodes.length ?? 0) > 0
)

const STATE_TEXT: Record<string, string> = { MISSING: '缺失', BLOCKED: '已熔断', IN_FLIGHT: '在途' }
const stateText = (s: string) => STATE_TEXT[s] ?? s
const stateColor = (s: string) => (s === 'BLOCKED' ? 'error' : s === 'IN_FLIGHT' ? 'info' : 'warning')

/** 与缺集体检的「立即补搜」同一个接口：同步等结果（最长几分钟），完了重新诊断一遍 */
async function searchNow() {
  const target = diagnosisTarget.value
  if (!target) return
  searching.value = true
  try {
    const result = await searchMissingApi(target.id)
    message.success(result || '补搜完成')
    // 弹窗可能已经被关掉、或换成了另一条订阅，那时不要把旧订阅的诊断刷回去
    if (diagnosisOpen.value && diagnosisTarget.value?.id === target.id) {
      await showDiagnosis(target)
    }
  } catch (e) {
    // 拦截器已经弹过后端的 message（落空时带着真实原因），这里不再盖一条
    console.error(e)
  } finally {
    searching.value = false
  }
}
</script>

<style scoped lang="scss">
.diag-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.diag-ep {
  padding: 10px 0;
  border-bottom: 1px solid var(--osr-border-light);

  &:last-of-type {
    border-bottom: none;
  }
}

.diag-ep-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.diag-ep-label {
  font-weight: 600;
  color: var(--osr-text-primary);
}

.diag-ep-meta {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.diag-ep-summary {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--osr-text-regular, var(--osr-text-primary));
}

.diag-reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.diag-more {
  margin-top: 8px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}
</style>
