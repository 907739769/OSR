<template>
  <div class="page-container">
    <PageHeader
      icon="funnel"
      title="PT 过滤规则"
      desc="全局的种子硬性过滤与择优排序规则，可被单条订阅覆盖"
    />

    <v-card class="table-card preview-card">
      <v-card-text>
        <SectionDivider>用一句话描述规则（AI）</SectionDivider>
        <p class="preview-desc">
          比如「只要 4K，体积不超过 30G，不要杜比视界，有 H&amp;R 的站点别下」。AI 会把描述改进下方表单，<b>不会自动保存</b>——
          确认无误（可以先到页面底部跑一次历史回放）再点保存。需要先在「参数设置 → OpenAI 配置」里填好 API Key。
        </p>
        <div class="inline-fields preview-fields">
          <v-text-field
            v-model="aiText"
            label="想要什么样的种子"
            density="compact"
            variant="outlined"
            hide-details
            class="field-lg"
            @keyup.enter="runAiDraft"
          />
          <v-btn color="primary" variant="flat" prepend-icon="wand-sparkles" :loading="aiDrafting" @click="runAiDraft">
            生成草稿
          </v-btn>
        </div>
        <div v-if="aiDraft" class="preview-result">
          <v-alert type="info" variant="tonal" density="compact">
            {{ aiDraft.explanation || '（AI 没有给出说明）' }}
            <template v-if="Object.keys(aiDraft.changes).length">
              <br>已改动：{{ Object.keys(aiDraft.changes).map(fieldLabel).join('、') }}
            </template>
          </v-alert>
          <v-alert v-if="aiDraft.dropped.length" type="warning" variant="tonal" density="compact" class="mt-2">
            以下内容没有采纳：{{ aiDraft.dropped.join('；') }}
          </v-alert>
        </div>
      </v-card-text>
    </v-card>

    <v-card :loading="refreshing" class="table-card">
      <v-card-text>
        <!-- 首屏骨架：表单未加载时是一份默认值，直接摆出来的话数据一到整页数值跳变一遍 -->
        <SkeletonForm v-if="firstLoading" dense :sections="4" :fields="4" />
        <v-form v-else ref="formRef" class="filter-form">
          <SectionDivider>硬性过滤（不满足即淘汰）</SectionDivider>

          <FormField>
            <v-text-field
              v-model.number="form.minSeeders"
              label="最低做种数"
              type="number"
              min="0"
              density="comfortable"
              variant="outlined"
              class="field-num-lg"
              :rules="toRuleFns(rules.minSeeders)"
            />
            <template #tip>
              做种数低于此值的种子直接淘汰
            </template>
          </FormField>

          <FormField label="体积按每集判定">
            <v-radio-group v-model="form.sizePerEpisode" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              开启后，下方三个体积阈值都按<strong>单集</strong>比较：区间包与季包的整包体积会先除以它覆盖的集数。
              剧集的种子常常是多集打包，整包体积是单集的几倍到几十倍——关掉的话，按单集设的上限会把所有多集包一刀切光，
              按季包设的下限又会放行所有单集垃圾资源，同一份阈值不可能对两者同时成立。
              单集资源折算前后完全一致，因此这个开关只影响多集包
            </template>
          </FormField>

          <div class="size-row">
            <FormField>
              <v-text-field
                v-model.number="form.minSize"
                label="体积下限"
                type="number"
                min="0"
                step="0.01"
                max="999"
                suffix="GB"
                density="comfortable"
                variant="outlined"
                class="field-num"
                :rules="toRuleFns(rules.size)"
                :error-messages="sizeRangeError"
              />
            </FormField>
            <FormField>
              <v-text-field
                v-model.number="form.maxSize"
                label="体积上限"
                type="number"
                min="0"
                step="0.01"
                max="999"
                suffix="GB"
                density="comfortable"
                variant="outlined"
                class="field-num"
                :rules="toRuleFns(rules.size)"
              />
            </FormField>
          </div>
          <div class="size-tip">
            0 表示不限{{ form.sizePerEpisode === '1' ? '；当前按每集判定' : '' }}
          </div>

          <FormField label="仅要免费种">
            <v-radio-group v-model="form.freeOnly" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              开启后 50% 促销种也会被淘汰，只留完全免费的
            </template>
          </FormField>

          <FormField label="外语电影需中字">
            <v-radio-group v-model="form.requireChineseSubtitle" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              外语电影（TMDb 原始语言非中文）的种子标题或描述中未检测到中文字幕标识（CHS/CHT/中字等）时直接淘汰。中文电影自动跳过此规则
            </template>
          </FormField>

          <FormField label="规避 H&R 站点">
            <v-radio-group v-model="form.avoidHitAndRun" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              开启后<strong>直接淘汰</strong>来自有 H&amp;R 考核站点的所有种子。H&amp;R 站点往往正是资源质量最好的站点，
              多数情况你要的其实是「同等条件下优先用没有考核的」——那个请把下方的「H&amp;R 规避」排序维度往前调，
              而不是打开这个开关。站点是否有 H&amp;R 在索引器管理页配置
            </template>
          </FormField>

          <FormField>
            <CsvSelect
              v-model="form.resolutionWhitelist"
              :options="vocabulary.resolutions"
              label="分辨率白名单"
              placeholder="留空表示不限"
            />
            <template #tip>
              <strong>硬性过滤</strong>：不在白名单内的分辨率直接淘汰。解析不出分辨率的种子在白名单非空时也会被淘汰
            </template>
          </FormField>

          <FormField>
            <CsvSelect
              v-model="form.sourceWhitelist"
              :options="vocabulary.sources"
              label="媒介来源白名单"
              placeholder="留空表示不限"
            />
            <template #tip>
              <strong>硬性过滤</strong>：不在白名单内的来源直接淘汰，解析不出来源的种子在白名单非空时也会被淘汰。
              REMUX 单独算一种来源；白名单里没写 REMUX 但写了 BluRay 时，REMUX 按 BluRay 放行
            </template>
          </FormField>

          <FormField>
            <CsvSelect
              v-model="form.requiredTags"
              :options="vocabulary.tags"
              label="必需质量标签"
              placeholder="留空表示不限"
            />
            <template #tip>
              种子须<strong>全部具备</strong>（AND 语义）才放行。整词匹配，选 HDR 不会命中 HDR10。
              要表达「任选其一」请改用下面的标题包含词
            </template>
          </FormField>

          <FormField>
            <CsvSelect
              v-model="form.excludeTags"
              :options="vocabulary.tags"
              label="排除质量标签"
              placeholder="命中任一即淘汰"
            />
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.includeKeywords"
              label="标题包含词"
              placeholder="逗号分隔，命中其一即可；留空表示不限"
              density="comfortable"
              variant="outlined"
            />
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.excludeKeywords"
              label="标题排除词"
              placeholder="逗号分隔，命中任一即淘汰"
              density="comfortable"
              variant="outlined"
            />
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.descriptionExcludeKeywords"
              label="描述排除词"
              placeholder="如 原盘,BDMV；留空表示不限"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              逗号分隔，命中任一即淘汰，判定对象是<strong>种子描述</strong>而非标题。用来拦标题里看不出、只在描述中标注的属性——最典型的是蓝光原盘：标题与压制版逐字同构（两者都解析成 BluRay，来源白名单分不开），体积上限又会把体积相近的 REMUX 一起切掉。<strong>描述为空的种子一律放行</strong>，因为不少索引器不返回描述字段
            </template>
          </FormField>

          <SectionDivider>择优排序（从存活的候选里挑一个）</SectionDivider>

          <FormField label="分辨率优先级">
            <PriorityListField
              v-model="form.resolutionPriority"
              :options="vocabulary.resolutions"
              label="分辨率"
              empty-text="未设置：分辨率不参与排序"
            />
            <template #tip>
              <strong>只影响排序</strong>，不做过滤——不在此列表内的分辨率只是排在最后，仍可能被下载。要过滤请用上面的白名单。
              洗版规则也按这里判断分辨率的高低
            </template>
          </FormField>

          <FormField label="媒介来源优先级">
            <PriorityListField
              v-model="form.sourcePriority"
              :options="vocabulary.sources"
              label="来源"
              empty-text="未设置：来源不参与排序，洗版也分不出来源高低"
            />
            <template #tip>
              <strong>只影响排序</strong>，不做过滤。同分辨率下 Remux 与 HDTV 的观感差距远大于做种数差距，
              通常应把下方的「媒介来源优先级」维度排在「做种数」之前。洗版规则也按这里判断来源的高低
            </template>
          </FormField>

          <FormField label="发布组优先级">
            <PriorityListField
              v-model="form.releaseGroupPriority"
              allow-custom
              label="发布组"
              empty-text="未设置：发布组不参与排序"
            />
            <template #tip>
              <strong>只影响排序</strong>：不在列表内的发布组只是排最后，仍可能被下载。要彻底排除某个发布组请用「种子黑名单」
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model.number="form.preferredSize"
              label="偏好体积"
              type="number"
              min="0"
              step="0.01"
              max="999"
              suffix="GB"
              density="comfortable"
              variant="outlined"
              class="field-num"
              :rules="toRuleFns(rules.size)"
            />
            <template #tip>
              0 表示体积不参与择优比较{{ form.sizePerEpisode === '1' ? '；当前按每集判定' : '' }}
            </template>
          </FormField>

          <FormField label="维度优先顺序">
            <OrderedList v-model="sortOrder" :label-of="labelOf" />
            <template #tip>
              排在前面的维度先比较。例如把「促销优先」放到「分辨率优先级」之前，就表示宁可要免费的 1080p，也不要收费的 4K
            </template>
          </FormField>
        </v-form>
      </v-card-text>
    </v-card>

    <v-card class="table-card preview-card">
      <v-card-text>
        <SectionDivider>规则试算</SectionDivider>
        <p class="preview-desc">
          粘贴一条种子标题，看它会被解析成什么、按<strong>当前页面上</strong>的规则（含未保存的修改）会不会被淘汰。黑名单按已保存的算。
        </p>
        <v-text-field
          v-model="previewForm.title"
          label="种子标题"
          placeholder="如 Some.Show.S01E01.2160p.WEB-DL.H265.DDP5.1-GRP"
          density="comfortable"
          variant="outlined"
          @keydown.enter.prevent="runPreview"
        />
        <v-textarea
          v-model="previewForm.description"
          label="种子描述（可选）"
          rows="2"
          auto-grow
          density="comfortable"
          variant="outlined"
        />
        <div class="inline-fields preview-fields">
          <v-text-field
            v-model="previewForm.sizeGb"
            label="体积"
            type="number"
            min="0"
            step="0.01"
            suffix="GB"
            density="comfortable"
            variant="outlined"
            hide-details
            class="field-num"
          />
          <v-text-field
            v-model="previewForm.seeders"
            label="做种数"
            type="number"
            min="0"
            density="comfortable"
            variant="outlined"
            hide-details
            class="field-num"
          />
          <v-checkbox-btn v-model="previewForm.free" label="免费" />
          <v-checkbox-btn v-model="previewForm.hitAndRun" label="H&R 站点" />
          <v-checkbox-btn v-model="previewForm.foreignMovie" label="按外语电影判定" />
        </div>
        <v-btn color="primary" variant="flat" prepend-icon="flask-conical" :loading="previewing" @click="runPreview">
          试算
        </v-btn>

        <div v-if="previewResult" class="preview-result">
          <v-alert
            :type="previewResult.accepted ? 'success' : 'error'"
            variant="tonal"
            density="comfortable"
          >
            <template v-if="previewResult.accepted">通过全部硬性过滤</template>
            <template v-else>
              <strong>被淘汰：{{ previewResult.rejectLabel }}</strong>
              <div class="preview-reason">{{ previewResult.rejectReason }}</div>
            </template>
          </v-alert>
          <div class="preview-parsed">
            <div v-for="row in parsedRows" :key="row.label" class="preview-row">
              <span class="preview-label">{{ row.label }}</span>
              <span class="preview-value">{{ row.value }}</span>
            </div>
          </div>
        </div>
      </v-card-text>
    </v-card>

    <v-card class="table-card preview-card">
      <v-card-text>
        <SectionDivider>历史回放</SectionDivider>
        <p class="preview-desc">
          拿最近搜到过的候选，分别用<b>已保存的规则</b>和<b>正在编辑的规则</b>判一遍，列出结论会变的——保存之前先看清这次修改会多放进来什么、多挡掉什么。
          较早的匹配日志没有记录体积、做种数等信息，这部分候选只比按标题判断的规则（分辨率、来源、关键词、发布组、质量标签）。
        </p>
        <div class="inline-fields preview-fields">
          <v-select
            v-model="replayDays"
            :items="[{ title: '最近 3 天', value: 3 }, { title: '最近 7 天', value: 7 }, { title: '最近 30 天', value: 30 }]"
            label="回放范围"
            density="compact"
            variant="outlined"
            hide-details
            class="field-md"
          />
          <v-btn color="primary" variant="flat" prepend-icon="history" :loading="replaying" @click="runReplay">
            回放
          </v-btn>
        </div>
        <div v-if="replayResult" class="preview-result">
          <v-alert :type="replayResult.changed ? 'warning' : 'info'" variant="tonal" density="compact">
            回放了 {{ replayResult.evaluated }} 个候选<template v-if="replayResult.titleOnly">（其中 {{ replayResult.titleOnly }} 个只按标题比较）</template>：
            <template v-if="replayResult.changed">
              {{ replayResult.newlyAccepted.total }} 个会<b>新通过</b>，{{ replayResult.newlyRejected.total }} 个会<b>新被淘汰</b>
            </template>
            <template v-else>结论全部不变<template v-if="!isDirty">（还没有改动规则）</template></template>
          </v-alert>
          <template v-for="group in replayGroups" :key="group.key">
            <div v-if="group.data.examples.length" class="replay-group">
              <div class="replay-group-title">{{ group.title }}（{{ group.data.total }}）</div>
              <div v-for="(c, i) in group.data.examples" :key="i" class="replay-row">
                <div class="replay-torrent" :title="c.torrentTitle">{{ c.torrentTitle }}</div>
                <div class="replay-meta">
                  《{{ c.subscriptionTitle }}》 · {{ group.reasonPrefix }}{{ c.reason }}<template v-if="c.titleOnly"> · 只按标题比较</template>
                </div>
              </div>
              <div v-if="group.data.total > group.data.examples.length" class="replay-meta">
                只列出前 {{ group.data.examples.length }} 个
              </div>
            </div>
          </template>
        </div>
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
import PriorityListField from '@/components/PriorityListField.vue'
import ConfigSaveBar from '@/components/ConfigSaveBar.vue'
import SkeletonForm from '@/components/skeleton/SkeletonForm.vue'
import { useFirstLoad } from '@/composables/useFirstLoad'
import { usePtFilterConfig } from '@/composables/usePtFilterConfig'
import { toRuleFns } from '@/composables/formRules'
import { formatSize } from '@/composables/sizeUnits'

const {
  loading, saving, formRef, form, rules, sizeRangeError, sortOrder, vocabulary,
  labelOf, save, discard, isDirty,
  previewForm, previewing, previewResult, runPreview,
  replayDays, replaying, replayResult, runReplay,
  aiText, aiDrafting, aiDraft, runAiDraft
} = usePtFilterConfig()

/** 回放结果的两组：新通过的列「现在为什么被挡」，新淘汰的列「改完之后为什么被挡」 */
const replayGroups = computed(() => replayResult.value ? [
  { key: 'accepted', title: '会新通过', reasonPrefix: '现在被淘汰：', data: replayResult.value.newlyAccepted },
  { key: 'rejected', title: '会新被淘汰', reasonPrefix: '改后被淘汰：', data: replayResult.value.newlyRejected }
] : [])
const { firstLoading, refreshing } = useFirstLoad(loading)

/** AI 草稿改动的字段名 → 表单上的叫法 */
const FIELD_LABELS: Record<string, string> = {
  minSeeders: '最低做种数', minSize: '体积下限', maxSize: '体积上限', preferredSize: '偏好体积',
  sizePerEpisode: '按每集判定体积', freeOnly: '仅免费种', includeKeywords: '包含关键词',
  excludeKeywords: '排除关键词', descriptionExcludeKeywords: '描述排除关键词',
  resolutionWhitelist: '分辨率白名单', resolutionPriority: '分辨率优先级', sourceWhitelist: '来源白名单',
  sourcePriority: '来源优先级', requiredTags: '必需标签', excludeTags: '排除标签',
  releaseGroupPriority: '发布组优先级', requireChineseSubtitle: '外语电影需中字', avoidHitAndRun: '规避 H&R'
}
const fieldLabel = (key: string) => FIELD_LABELS[key] ?? key

/** 解析结果只列有值的项，缺失的整行不写 */
const parsedRows = computed(() => {
  const r = previewResult.value
  if (!r) return []
  const episode = r.episode == null
    ? (r.season != null ? `第 ${r.season} 季整季` : '')
    : `S${r.season ?? '?'}E${r.episode}${r.episodeEnd && r.episodeEnd > r.episode ? `-E${r.episodeEnd}` : ''}`
  return [
    { label: '标题', value: r.title },
    { label: '年份', value: r.year },
    { label: '季集', value: episode },
    { label: '覆盖集数', value: r.episodeCount > 1 ? String(r.episodeCount) : '' },
    { label: '分辨率', value: r.resolution },
    { label: '来源', value: r.source },
    { label: '发布组', value: r.releaseGroup },
    { label: '质量标签', value: r.tags?.join(' / ') },
    { label: '判定体积', value: r.effectiveSize > 0 ? formatSize(r.effectiveSize) : '' }
  ].filter((row) => row.value)
})
</script>

<style scoped>
/* 历史回放：每个候选两行，标题一行截断、所属订阅与原因一行 */
.replay-group {
  margin-top: 12px;
}

.replay-group-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
  color: var(--osr-text-primary);
}

.replay-row {
  padding: 4px 0;
  border-bottom: 1px dashed var(--osr-border-light);
}

.replay-torrent {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.replay-meta {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

/* 数字输入框限宽，避免「最低做种数」这类两三位数的框拉满整行 */
.field-num-lg {
  max-width: 200px;
}

.field-num {
  max-width: 160px;
}

.size-row {
  display: flex;
  gap: 16px;
}

.size-tip {
  margin: -8px 0 16px;
  font-size: var(--osr-fs-xs);
  color: var(--osr-text-secondary);
}

.preview-card {
  margin-top: 16px;
}

.preview-desc {
  margin-bottom: 12px;
  font-size: var(--osr-fs-sm);
  color: var(--osr-text-secondary);
}

.preview-fields {
  align-items: center;
  margin-bottom: 12px;

  /* v-checkbox-btn 默认 flex: 1，会把三个勾选框摊满整行 */
  :deep(.v-selection-control) {
    flex: 0 0 auto;
  }
}

.preview-result {
  margin-top: 16px;
}

.preview-reason {
  margin-top: 2px;
  font-size: var(--osr-fs-sm);
}

.preview-parsed {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 4px 16px;
  margin-top: 12px;
}

.preview-row {
  display: flex;
  gap: 8px;
  font-size: var(--osr-fs-sm);
}

.preview-label {
  flex-shrink: 0;
  width: 64px;
  color: var(--osr-text-secondary);
}

.preview-value {
  min-width: 0;
  overflow-wrap: anywhere;
}

@media (max-width: 768px) {
  .page-container {
    padding: 0;
  }

  .filter-form {
    width: 100%;
  }

  .field-num-lg,
  .field-num {
    max-width: 100%;
  }

  .size-row {
    flex-direction: column;
    gap: 0;
  }
}
</style>
