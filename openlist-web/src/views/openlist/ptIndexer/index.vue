<template>
  <div class="page-container">
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
            <v-chip :color="item.enabled === '1' ? 'success' : 'error'" size="small" variant="tonal">
              {{ item.enabled === '1' ? '启用' : '停用' }}
            </v-chip>
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
            <div class="card-row">
              <span class="label">上次轮询</span>
              <span class="value">{{ item.lastPollTime || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">上次结果</span>
              <span class="value">
                <span v-if="!item.lastStatus">-</span>
                <v-chip v-else-if="item.lastStatus === 'OK'" color="success" size="small" variant="tonal">正常</v-chip>
                <v-chip v-else color="error" size="small" variant="tonal">{{ item.lastStatus }}</v-chip>
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
            <div class="form-item">
              <label class="form-label">分类</label>
              <div class="category-field">
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
              </div>
            </div>
            <div class="form-item">
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
            </div>
            <div class="form-item">
              <label class="form-label">状态</label>
              <v-radio-group v-model="form.enabled" inline hide-details>
                <v-radio label="启用" value="1" />
                <v-radio label="停用" value="0" />
              </v-radio-group>
            </div>
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
import { ref, computed } from 'vue'
import { usePtIndexer } from '@/composables/usePtIndexer'

const showSearch = ref(window.innerWidth >= 768)

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  selectedIds, single, multiple, toggleSelect,
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
const apiKeyRules = toRuleFns(rules.apiKey)
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

/* ============================================
   卡片网格
   ============================================ */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
  min-height: 120px;
}

.item-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;

  .card-checkbox {
    flex-shrink: 0;
    display: flex;

    :deep(.v-selection-control) {
      min-height: unset;
    }
  }

  .card-title {
    flex: 1;
    min-width: 0;
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.card-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;

  .label {
    flex-shrink: 0;
    width: 64px;
    color: var(--osr-text-secondary);
  }

  .value {
    flex: 1;
    min-width: 0;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.card-footer {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  padding-top: 8px;
  border-top: 1px solid var(--osr-border-light);
}

.form-item {
  margin-bottom: 16px;

  .form-label {
    display: block;
    margin-bottom: 6px;
    font-size: 13px;
    color: var(--osr-text-secondary);
  }
}

.category-field {
  display: flex;
  gap: 8px;
  align-items: flex-start;

  .v-select {
    flex: 1;
    min-width: 0;
  }
}

@media (max-width: 768px) {

  

  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
