<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-magnify-scan"
      title="PT 索引器"
      desc="配置 Torznab 接口，用于 RSS 轮询与搜索补集"
    />

    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
          <v-text-field
            v-model="queryParams.name"
            label="名称"
            placeholder="请输入名称"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.enabled"
            label="状态"
            :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            class="status-select"
          />
          <div class="search-actions">
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
          </div>
        </div>
      </v-form>
    </v-card>

    <!-- Table Card -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd('新增索引器')">
            新增
          </v-btn>
          <v-btn color="success" prepend-icon="mdi-pencil-outline" :disabled="single" @click="handleUpdate(undefined, '修改索引器')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的索引器？`)">
            批量删除
          </v-btn>
          <v-checkbox
            :model-value="isAllPageSelected"
            :indeterminate="isIndeterminate"
            density="compact"
            hide-details
            class="select-all-checkbox"
            label="全选本页"
            @update:model-value="(v: boolean | null) => toggleSelectAllPage(!!v)"
          />
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <div class="card-grid">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <div v-for="item in taskList" :key="item.id" class="item-card">
          <div class="card-header">
            <div class="card-checkbox">
              <v-checkbox
                :model-value="selectedIds.includes(item.id)"
                density="compact"
                hide-details
                @update:model-value="toggleSelect(item.id)"
              />
            </div>
            <span class="card-title" :title="item.name">{{ item.name }}</span>
            <StatusChip :value="item.enabled" />
          </div>
          <div class="card-body">
            <div class="card-row">
              <span class="label">接口地址</span>
              <span class="value" :title="item.url">{{ item.url }}</span>
            </div>
            <div class="card-row">
              <span class="label">分类</span>
              <span class="value">{{ item.categories || '不限' }}</span>
            </div>
            <div class="card-row">
              <span class="label">轮询周期</span>
              <span class="value">{{ item.pollInterval }} 秒</span>
            </div>
            <div class="card-row" v-if="item.hrEnabled === '1'">
              <span class="label">H&amp;R</span>
              <span class="value">
                <StatusChip type="warning" :text="hrLabel(item)" />
              </span>
            </div>
            <div class="card-row">
              <span class="label">上次轮询</span>
              <span class="value">{{ item.lastPollTime || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">上次结果</span>
              <span class="value">
                <span v-if="!item.lastStatus">-</span>
                <StatusChip v-else-if="item.lastStatus === 'OK'" type="success" text="正常" />
                <StatusChip v-else type="error" :text="item.lastStatus" />
              </span>
            </div>
            <div class="card-row" v-if="item.failCount > 0">
              <span class="label">连续失败</span>
              <span class="value">
                <v-chip :color="item.failCount >= 10 ? 'error' : 'warning'" size="small" variant="tonal">
                  {{ item.failCount }} 次
                </v-chip>
              </span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改索引器')">
              修改
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无索引器" />
      </div>

      <div class="pagination-wrapper">
        <span class="total-text">共 {{ total }} 条</span>
        <v-select
          :model-value="queryParams.pageSize"
          :items="[12, 24, 48]"
          density="compact"
          variant="outlined"
          hide-details
          class="page-size-select"
          @update:model-value="(v: number) => { queryParams.pageSize = v; queryParams.pageNum = 1; getList() }"
        />
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>

    <!-- Add/Edit Dialog -->
    <v-dialog v-model="open" max-width="600">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field
              v-model="form.name"
              label="名称"
              placeholder="请输入名称"
              density="comfortable"
              variant="outlined"
              :rules="nameRules"
            />
            <v-text-field
              v-model="form.url"
              label="接口地址"
              placeholder="如 http://jackett:9117/api/v2.0/indexers/xxx/results/torznab/api"
              density="comfortable"
              variant="outlined"
              :rules="urlRules"
            />
            <v-text-field
              v-model="form.apiKey"
              label="apikey"
              type="password"
              :placeholder="form.id ? '留空则不修改 apikey' : '请输入 Torznab apikey'"
              density="comfortable"
              variant="outlined"
              :rules="apiKeyRules"
            />
            <FormField label="分类">
              <v-select
                v-model="categoriesSelected"
                :items="categoryFlatOptions"
                multiple
                chips
                closable-chips
                density="comfortable"
                variant="outlined"
                hide-details
                placeholder="点击右侧「获取分类」后选择，或直接输入分类 ID"
              />
              <v-btn :loading="categoriesLoading" variant="outlined" @click="fetchCategories">获取分类</v-btn>
            </FormField>
            <v-text-field
              v-model.number="form.pollInterval"
              label="轮询周期"
              type="number"
              min="60"
              step="60"
              density="comfortable"
              variant="outlined"
              :rules="pollIntervalRules"
              suffix="秒"
            />
            <FormField label="状态">
              <v-radio-group v-model="form.enabled" inline hide-details>
                <v-radio label="启用" value="1" />
                <v-radio label="停用" value="0" />
              </v-radio-group>
            </FormField>

            <FormField label="H&R 考核">
              <v-radio-group v-model="form.hrEnabled" inline hide-details density="comfortable">
                <v-radio label="无" value="0" />
                <v-radio label="有" value="1" />
              </v-radio-group>
              <template #tip>
                该站点是否有 Hit&nbsp;and&nbsp;Run 考核。开启后，来自本站的种子下载完成会进入保种追踪：
                达标前从下载器消失会立刻告警，达标后通知可安全删除；推送时还会把下面的要求写成种子的分享限额，
                防止下载器的自动管理提前把种子清掉
              </template>
            </FormField>

            <template v-if="form.hrEnabled === '1'">
              <v-text-field
                v-model.number="form.hrSeedHours"
                label="最短做种时长"
                type="number"
                min="0"
                density="comfortable"
                variant="outlined"
                suffix="小时"
              />
              <FormField>
                <v-text-field
                  v-model.number="form.hrRatio"
                  label="最低分享率"
                  type="number"
                  min="0"
                  step="0.1"
                  density="comfortable"
                  variant="outlined"
                />
                <template #tip>
                  两项是<strong>或</strong>的关系，满足任一即视为达标（站点通行表述就是「做满 N 小时或分享率达到 R」）。
                  不考核的那一项填 0。<strong>两项都填 0 等于没配</strong>，后端会按未开启 H&amp;R 处理。
                  另外 Transmission 的 RPC 没有「最短做种时长」这个概念，该项对 Transmission 只能靠 OSR 侧追踪告警兜底
                </template>
              </FormField>
            </template>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-btn :loading="testLoading" variant="outlined" @click="handleTest">测试连接</v-btn>
          <v-spacer />
          <v-btn variant="outlined" @click="open = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="handleSubmitClick">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import { ref, computed } from 'vue'
import FormField from '@/components/FormField.vue'
import { usePtIndexer } from '@/composables/usePtIndexer'

const showSearch = ref(window.innerWidth >= 768)

/** 列表卡片上的 H&R 要求摘要。两项是「或」的关系，只填了一项就只显示那一项 */
const hrLabel = (item: any) => {
  const parts: string[] = []
  if (item.hrSeedHours > 0) parts.push(`做满 ${item.hrSeedHours}h`)
  if (item.hrRatio > 0) parts.push(`分享率 ${item.hrRatio}`)
  return parts.length ? parts.join(' 或 ') : '未配置阈值'
}

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  selectedIds, single, multiple, toggleSelect,
  isAllPageSelected, isIndeterminate, toggleSelectAllPage,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest,
  categoriesLoading, categoryOptions, fetchCategories, categoriesSelected
} = usePtIndexer()

// 表单规则是 { required, message, trigger }/{ pattern, type, min } 对象格式（composable 返回），
// Vuetify 的 v-text-field :rules 需要函数格式，这里就地转换，不改动 composable
const toRuleFns = (ruleList: any[]) =>
  (ruleList || []).map((rule: any) => (value: any) => {
    if (rule.required && (value === null || value === undefined || value === '')) {
      return rule.message || '不能为空'
    }
    if (rule.pattern && value && !rule.pattern.test(value)) {
      return rule.message || '格式不正确'
    }
    if (rule.type === 'number' && rule.min !== undefined && value !== null && value !== undefined && Number(value) < rule.min) {
      return rule.message || `不得小于 ${rule.min}`
    }
    return true
  })

const nameRules = toRuleFns(rules.name)
const urlRules = toRuleFns(rules.url)
// 编辑时后端出于安全考虑会把 apikey 脱敏为空（留空提交 = 沿用已保存值），
// 只有新增时才要求必填，否则编辑弹窗永远校验不过
const apiKeyRules = computed(() => (form.value.id ? [] : toRuleFns(rules.apiKey)))
const pollIntervalRules = toRuleFns(rules.pollInterval)

const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}

// 原来的父子分类分组结构在 Vuetify v-select 中拍平为一层，父分类照常可选，
// 子分类前缀全角空格保留原有的缩进视觉效果
const categoryFlatOptions = computed(() => {
  const list: { title: string; value: string }[] = []
  categoryOptions.value.forEach(parent => {
    list.push({ title: `${parent.name} (${parent.id})`, value: String(parent.id) })
    parent.children.forEach(child => {
      list.push({ title: `\u3000${child.name} (${child.id})`, value: String(child.id) })
    })
  })
  return list
})
</script>

<style scoped lang="scss">
/* .card-grid / .item-card / .card-header / .card-body / .card-row / .card-footer
   已统一由 styles/list.scss 提供，本页不再重复定义 */
</style>
