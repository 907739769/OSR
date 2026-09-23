<template>
  <div class="page-container">
    <PageHeader
      icon="square-arrow-up"
      title="PT 洗版规则"
      desc="已入库的集在质量未达目标时，自动搜索并下载更好的版本"
    />

    <v-card :loading="overviewLoading" class="table-card overview-card">
      <v-card-text>
        <div class="overview-head">
          <span class="overview-title">洗版状态</span>
          <StatusChip
            v-if="overview"
            :type="overview.running ? 'warning' : overview.active ? 'success' : 'info'"
            :text="overview.running ? '扫描中' : overview.active ? '已激活' : '未激活'"
            :pulse="overview.running"
          />
          <v-spacer />
          <v-btn variant="text" prepend-icon="refresh-cw" :loading="overviewLoading" @click="loadOverview">刷新</v-btn>
          <v-btn
            color="primary"
            variant="outlined"
            prepend-icon="play"
            :loading="scanning"
            :disabled="!overview?.active || overview?.running"
            @click="scanNow"
          >立即扫描</v-btn>
        </div>

        <div v-if="!overview && overviewLoading" class="overview-stats osr-skeleton" aria-hidden="true">
          <div v-for="i in 6" :key="i" class="overview-stat">
            <span class="osr-bone osr-bone--title" style="width: 40px; height: 24px" />
            <span class="osr-bone osr-bone--caption" style="width: 64px; margin-top: 8px" />
          </div>
        </div>
        <div v-if="overview" class="overview-stats">
          <div v-for="stat in stats" :key="stat.label" class="overview-stat">
            <div class="overview-num">{{ stat.value }}</div>
            <div class="overview-label">{{ stat.label }}</div>
          </div>
        </div>
        <div v-if="overview" class="overview-meta">
          <span>{{ lastScanText }}</span>
          <span v-if="overview.nextScanAt">下次扫描不早于 {{ formatDateTime(overview.nextScanAt) }}</span>
        </div>
      </v-card-text>
    </v-card>

    <v-card :loading="refreshing" class="table-card">
      <v-card-text>
        <v-alert type="warning" variant="tonal" density="comfortable" class="notice">
          <strong>洗版不会删除旧版本。</strong>
          OSR 从不删除种子和文件，新版本下载完成后新旧两个版本会同时存在，
          媒体库里会出现同一集的两个版本，需要你自行清理旧的。
          自动清理会在后续版本提供，届时会先检查旧种子的 H&amp;R 是否已达标再动手。
        </v-alert>

        <!-- 首屏骨架：表单未加载时是一份默认值，直接摆出来的话数据一到整页数值跳变一遍 -->
        <SkeletonForm v-if="firstLoading" dense :sections="3" :fields="4" />
        <v-form v-else ref="formRef" class="filter-form">
          <SectionDivider>总开关</SectionDivider>

          <FormField label="启用洗版">
            <v-radio-group v-model="form.enabled" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              默认关闭。开启前请先在下方确认目标质量——没有目标质量的话，每一集都会永远搜下去，
              把索引器配额烧干
            </template>
          </FormField>

          <SectionDivider>目标质量（达到即停止洗版）</SectionDivider>

          <v-alert
            v-if="form.enabled === '1' && !hasTarget()"
            type="error"
            variant="tonal"
            density="compact"
            class="notice"
          >
            已开启洗版但没有配置任何目标质量，不会有任何集被判定为需要升级。请至少填写一项。
          </v-alert>

          <v-alert
            v-if="problems.length"
            :type="form.enabled === '1' ? 'error' : 'warning'"
            variant="tonal"
            density="compact"
            class="notice"
          >
            <div class="problem-title">
              洗版规则与过滤规则页的优先级对不上{{ form.enabled === '1' ? '，这样保存会被拒绝' : '，开启前需要先处理' }}：
            </div>
            <ul class="problem-list">
              <li v-for="p in problems" :key="p">{{ p }}</li>
            </ul>
          </v-alert>

          <FormField>
            <v-select
              v-model="form.targetResolution"
              :items="resolutionItems"
              label="目标分辨率"
              placeholder="不约束"
              clearable
              density="comfortable"
              variant="outlined"
              class="field-select"
            />
            <template #tip>
              按过滤规则页「分辨率优先级」中的名次比较，因此目标填 1080p 时，已经是 2160p 的集同样算达标
            </template>
          </FormField>

          <FormField>
            <CsvSelect
              v-model="form.targetSources"
              :options="vocabulary.sources"
              label="目标媒介来源"
              placeholder="不约束"
            />
            <template #tip>
              <strong>命中其一</strong>即满足该项。REMUX 单独算一种来源
            </template>
          </FormField>

          <FormField>
            <CsvSelect
              v-model="form.targetTags"
              :options="vocabulary.tags"
              label="目标质量标签"
              placeholder="不约束"
            />
            <template #tip>
              须<strong>全部具备</strong>才算达标。三项目标之间是「与」的关系，填了的项都要满足
            </template>
          </FormField>

          <SectionDivider>怎么算「更好」</SectionDivider>

          <FormField label="维度优先顺序">
            <OrderedList v-model="dimensionOrder" :label-of="labelOf" />
            <template #tip>
              按此顺序逐维度比较，第一个名次不同的维度说了算；全部并列则<strong>不换</strong>。
              这里刻意没有做种数、体积、促销——那些不是画质，把它们放进升级判定会导致同一集被反复替换。
            </template>
          </FormField>

          <FormField label="各维度内部的高低（沿用过滤规则）">
            <div class="priority-readout">
              <div v-for="row in priorityRows" :key="row.label" class="priority-row">
                <span class="priority-label">{{ row.label }}</span>
                <span v-if="row.value" class="priority-value">{{ row.value.split(',').join(' > ') }}</span>
                <span v-else class="priority-empty">未设置，该维度分不出高低</span>
              </div>
            </div>
            <template #tip>
              「2160p 比 1080p 好」只在一处定义，改这几项请到
              <router-link v-if="filterConfigPath" :to="filterConfigPath">过滤规则页</router-link>
              <template v-else>过滤规则页</template>。
              那边改了优先级之后，已达标的集会按新的顺序重新评估
            </template>
          </FormField>

          <SectionDivider>节流</SectionDivider>

          <FormField>
            <v-text-field
              v-model.number="form.maxConcurrent"
              label="同时在途洗版数"
              type="number"
              min="1"
              max="20"
              density="comfortable"
              variant="outlined"
              class="field-num"
              :rules="toRuleFns(rules.maxConcurrent)"
            />
            <template #tip>
              独立于补缺集的名额。缺集是刚需、洗版是锦上添花，这个值不宜设大，否则新剧的更新会被堵在门外
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model.number="form.maxSearchesPerRound"
              label="每轮最多搜索"
              type="number"
              min="1"
              max="500"
              suffix="次"
              density="comfortable"
              variant="outlined"
              class="field-num"
              :rules="toRuleFns(rules.maxSearchesPerRound)"
            />
            <template #tip>
              每一集的洗版搜索都要打一遍所有索引器。没搜到更好版本的集不占上面的名额，只靠它限不住搜索量；
              这里限的是每轮的搜索次数，按最久没搜过的先搜。连续落空的集会退避：等待时间按扫描周期逐次翻倍，最长 7 天
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model.number="form.scanIntervalHours"
              label="扫描周期"
              type="number"
              min="1"
              max="168"
              density="comfortable"
              variant="outlined"
              class="field-num"
              suffix="小时"
              :rules="toRuleFns(rules.scanIntervalHours)"
            />
          </FormField>
        </v-form>
      </v-card-text>
    </v-card>

    <ConfigSaveBar v-if="!loading" :dirty="isDirty" :saving="saving" @save="save" @discard="discard" />
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import FormField from '@/components/FormField.vue'
import SectionDivider from '@/components/SectionDivider.vue'
import OrderedList from '@/components/OrderedList.vue'
import CsvSelect from '@/components/CsvSelect.vue'
import ConfigSaveBar from '@/components/ConfigSaveBar.vue'
import SkeletonForm from '@/components/skeleton/SkeletonForm.vue'
import { useFirstLoad } from '@/composables/useFirstLoad'
import StatusChip from '@/components/StatusChip.vue'
import { usePtUpgradeConfig } from '@/composables/usePtUpgradeConfig'
import { toRuleFns } from '@/composables/formRules'
import { formatDateTime } from '@/composables/dateTime'
import { getRoutePathForComponent } from '@/router'

const {
  loading, saving, formRef, form, rules, dimensionOrder, vocabulary,
  labelOf, hasTarget, save, discard, isDirty,
  overview, overviewLoading, loadOverview, scanning, scanNow, problems
} = usePtUpgradeConfig()
const { firstLoading, refreshing } = useFirstLoad(loading)

const filterConfigPath = computed(() => getRoutePathForComponent('openlist/ptFilterConfig/index'))

/** 库里已存的、不在可选值里的历史值也要能显示出来 */
const resolutionItems = computed(() => {
  const list = [...vocabulary.value.resolutions]
  if (form.targetResolution && !list.some((r) => r.toLowerCase() === form.targetResolution!.toLowerCase())) {
    list.push(form.targetResolution)
  }
  return list
})

const stats = computed(() => {
  const c = overview.value?.counts
  if (!c) return []
  return [
    { label: '待搜索', value: c.pendingDue },
    { label: '退避中', value: c.pendingBackoff },
    { label: '洗版下载中', value: c.upgrading },
    { label: '已达标', value: c.reached },
    { label: '待评估', value: c.unevaluated },
    { label: '无质量基线', value: c.noBaseline }
  ]
})

const lastScanText = computed(() => {
  const last = overview.value?.lastScan
  if (!last) return '本次启动后还没有扫描过'
  const when = `上次扫描 ${formatDateTime(last.finishedAt)}${last.manual ? '（手动）' : ''}`
  if (last.error) return `${when}：失败，${last.error}`
  const o = last.outcome
  if (!o || !o.active) return `${when}：洗版未激活，未执行`
  return `${when}：评估 ${o.evaluated} 集，搜索 ${o.searched} 次，推送 ${o.pushed} 个${o.exhausted ? '，名额已用尽' : ''}`
})

const priorityRows = computed(() => {
  const p = overview.value?.filterPriorities || {}
  return [
    { label: '分辨率', value: p.resolutionPriority },
    { label: '媒介来源', value: p.sourcePriority },
    { label: '发布组', value: p.releaseGroupPriority }
  ]
})
</script>

<style scoped>
.notice {
  margin-bottom: 16px;
}

.overview-card {
  margin-bottom: 16px;
}

.overview-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.overview-title {
  font-size: var(--osr-fs-md);
  font-weight: 600;
}

.overview-stats {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  margin-top: 12px;
}

.overview-stat {
  padding: 8px 12px;
  border-radius: var(--osr-radius-md);
  background: var(--osr-bg-page);
}

.overview-num {
  font-size: var(--osr-fs-xl);
  font-weight: 600;
}

.overview-label {
  font-size: var(--osr-fs-xs);
  color: var(--osr-text-secondary);
}

.overview-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-top: 8px;
  font-size: var(--osr-fs-sm);
  color: var(--osr-text-secondary);
}

.problem-title {
  font-weight: 600;
}

.problem-list {
  margin: 4px 0 0;
  padding-left: 18px;
}

.field-num {
  max-width: 160px;
}

.field-select {
  max-width: 260px;
}

.priority-readout {
  width: 100%;
}

.priority-row {
  display: flex;
  gap: 12px;
  padding: 2px 0;
  font-size: var(--osr-fs-sm);
}

.priority-label {
  flex-shrink: 0;
  width: 64px;
  color: var(--osr-text-secondary);
}

.priority-value {
  min-width: 0;
  overflow-wrap: anywhere;
}

.priority-empty {
  color: rgb(var(--v-theme-error));
}

@media (max-width: 768px) {
  .page-container {
    padding: 0;
  }

  .filter-form {
    width: 100%;
  }

  .field-num,
  .field-select {
    max-width: 100%;
  }

  .overview-stats {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
