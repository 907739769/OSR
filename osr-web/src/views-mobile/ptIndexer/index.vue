<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.name"
        label="名称"
        placeholder="请输入名称"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.enabled"
        :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
        label="状态"
        placeholder="全部状态"
        clearable
        density="compact"
        variant="outlined"
      />
    </MobileSearchPanel>

    <!-- 批量操作 -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="error" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的索引器？`)">
        批量删除
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">取消</v-btn>
    </div>

    <!-- 新增 FAB -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增索引器')">
      新增
    </v-btn>

    <!-- 列表 -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card
        v-for="item in taskList"
        :key="item.id"
        class="task-card"
        :class="{ selected: selectedIds.includes(item.id) }"
        @click="handleCardClick($event, item.id)"
      >
        <div class="card-checkbox">
          <v-checkbox
            :model-value="selectedIds.includes(item.id)"
            density="compact"
            hide-details
            @click.stop="toggleSelect(item.id)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <span class="card-title">{{ item.name }}</span>
            <StatusChip :value="item.enabled" />
          </div>
          <div class="card-detail">
            <div class="detail-row">
              <span class="label">接口地址</span>
              <span class="value">{{ item.url }}</span>
            </div>
            <div class="detail-row">
              <span class="label">分类</span>
              <span class="value">{{ item.categories || '不限' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">轮询周期</span>
              <span class="value">{{ item.pollInterval }} 秒</span>
            </div>
            <div class="detail-row">
              <span class="label">上次轮询</span>
              <span class="value">{{ item.lastPollTime || '-' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">上次结果</span>
              <span class="value">
                <span v-if="!item.lastStatus">-</span>
                <v-chip v-else-if="item.lastStatus === 'OK'" color="success" size="small" variant="tonal">正常</v-chip>
                <v-chip v-else color="error" size="small" variant="tonal">{{ item.lastStatus }}</v-chip>
              </span>
            </div>
            <div class="detail-row" v-if="item.failCount > 0">
              <span class="label">连续失败</span>
              <span class="value">
                <v-chip :color="item.failCount >= 10 ? 'error' : 'warning'" size="small" variant="tonal">
                  {{ item.failCount }} 次
                </v-chip>
              </span>
            </div>
          </div>
          <div class="card-actions" @click.stop>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改索引器')">修改</v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">删除</v-btn>
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无索引器" />
    </div>

    <!-- 分页 -->
    <MobilePager
      v-model:page-size="queryParams.pageSize"
      :page-num="queryParams.pageNum"
      :total="total"
      :total-pages="totalPages"
      @prev="prevPage"
      @next="nextPage"
      @size-change="handleSizeChange"
    />

    <!-- 新增/编辑弹窗 -->
    <v-dialog v-model="open" width="92%">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field
              v-model="form.name"
              label="名称"
              placeholder="请输入名称"
              :rules="toRules(rules.name)"
              class="mb-2"
            />
            <v-text-field
              v-model="form.url"
              label="接口地址"
              placeholder="如 http://jackett:9117/api/v2.0/indexers/xxx/results/torznab/api"
              :rules="toRules(rules.url)"
              class="mb-2"
            />
            <v-text-field
              v-model="form.apiKey"
              label="apikey"
              type="password"
              :placeholder="form.id ? '留空则不修改 apikey' : '请输入 Torznab apikey'"
              :rules="toRules(rules.apiKey)"
              class="mb-2"
            />
            <div class="category-field mb-2">
              <v-select
                v-model="categoriesSelected"
                :items="categoryFlatOptions"
                multiple
                chips
                closable-chips
                label="分类"
                placeholder="点击右侧「获取分类」后选择，或直接输入分类 ID"
                density="comfortable"
                variant="outlined"
                hide-details
              />
              <v-btn :loading="categoriesLoading" variant="outlined" @click="fetchCategories">获取分类</v-btn>
            </div>
            <v-text-field
              v-model.number="form.pollInterval"
              label="轮询周期"
              type="number"
              min="60"
              step="60"
              suffix="秒"
              :rules="toRules(rules.pollInterval)"
              class="mb-2"
            />
            <v-radio-group v-model="form.enabled" inline label="状态" hide-details>
              <v-radio label="启用" value="1" />
              <v-radio label="停用" value="0" />
            </v-radio-group>
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
import { computed } from 'vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtIndexer } from '@/composables/usePtIndexer'

const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest,
  categoriesLoading, categoryOptions, fetchCategories, categoriesSelected,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePtIndexer()

// 与 PC 端一致：提交前先跑一遍 v-form 校验，校验不通过不提交
const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}

// 将对象格式的校验规则（composable 返回）转换为 Vuetify 的规则函数数组
const toRules = (fieldRules?: any[]) => {
  return (fieldRules || []).map((r: any) => (value: any) => {
    if (r.required && (value === undefined || value === null || value === '')) return r.message
    if (r.pattern && value && !r.pattern.test(value)) return r.message
    if (r.type === 'number' && r.min !== undefined && value !== null && value !== undefined && Number(value) < r.min) return r.message
    return true
  })
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
.category-field {
  display: flex;
  gap: 8px;
  align-items: flex-start;

  .v-select {
    flex: 1;
    min-width: 0;
  }
}
</style>
