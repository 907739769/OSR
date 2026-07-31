<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-form ref="queryRef">
        <v-select
          v-model="queryParams.type"
          label="类型"
          :items="[{ title: '种子(GUID)', value: 'GUID' }, { title: '发布组', value: 'RELEASE_GROUP' }]"
          placeholder="全部类型"
          clearable
          density="compact"
          variant="outlined"
          class="mb-3"
        />
        <v-text-field
          v-model="queryParams.displayValue"
          label="展示内容"
          placeholder="标题或发布组名"
          clearable
          density="compact"
          variant="outlined"
          @keyup.enter="handleQuery"
        />
      </v-form>
    </MobileSearchPanel>

    <!-- 新增 FAB -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增发布组黑名单')">
      新增
    </v-btn>

    <!-- 列表 -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <div v-for="item in taskList" :key="item.id" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <span class="task-name" :title="item.displayValue">{{ item.displayValue || '(无展示内容)' }}</span>
            <v-chip :color="item.type === 'GUID' ? 'error' : 'warning'" size="small" variant="tonal">
              {{ item.type === 'GUID' ? '种子' : '发布组' }}
            </v-chip>
          </div>
          <div class="card-detail">
            <div class="detail-row">
              <span class="label">匹配键</span>
              <span class="value">{{ item.type === 'GUID' ? shortHash(item.value) : item.value }}</span>
            </div>
            <div class="detail-row">
              <span class="label">原因</span>
              <span class="value">{{ item.reason || '-' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">创建时间</span>
              <span class="value">{{ item.createTime || '-' }}</span>
            </div>
          </div>
        </div>
        <div class="card-actions">
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
            删除
          </v-btn>
        </div>
      </div>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无黑名单规则" />
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

    <!-- 新增弹窗 -->
    <v-dialog v-model="open" max-width="90%">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field
              v-model="form.value"
              label="发布组名"
              placeholder="如 CHDWEB，大小写不敏感"
              :rules="valueRules"
              class="mb-3"
            />
            <v-textarea
              v-model="form.reason"
              label="原因"
              rows="2"
              placeholder="可选，如“转码质量差”"
            />
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="open = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="handleSubmitClick">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtTorrentBlacklist } from '@/composables/usePtTorrentBlacklist'

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, submitForm, handleDelete,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePtTorrentBlacklist()

// Element Plus 表单规则是 { required, message, trigger } 对象格式，
// Vuetify 的 v-text-field :rules 需要函数格式，这里就地转换，不改动 composable
const toRuleFns = (ruleList: any[]) =>
  (ruleList || []).map((rule: any) => (value: any) => {
    if (rule.required && (value === null || value === undefined || value === '')) {
      return rule.message || '不能为空'
    }
    return true
  })

const valueRules = toRuleFns(rules.value)

const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}

const shortHash = (value: string) => {
  if (!value) return '-'
  return value.length > 12 ? `${value.slice(0, 6)}...${value.slice(-4)}` : value
}
</script>

<style scoped lang="scss">
.mobile-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: calc(100vh - 120px);
  padding-bottom: 8px;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
  flex: 1;
}

.task-card {
  display: flex;
  gap: 10px;
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  padding: 12px;
  box-shadow: var(--osr-shadow-base);

  .card-content {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .card-top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;

    .task-name {
      font-size: 14px;
      font-weight: 600;
      color: var(--osr-text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      line-height: 1.4;
    }
  }

  .card-detail {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .detail-row {
    display: flex;
    gap: 8px;
    font-size: 12px;
    line-height: 1.6;

    .label {
      flex-shrink: 0;
      width: 62px;
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

  .card-actions {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    padding-left: 8px;
    border-left: 1px solid var(--osr-border-light);
  }
}

.fab-add {
  position: fixed;
  right: 20px;
  bottom: calc(56px + 16px + env(safe-area-inset-bottom, 0px));
  z-index: 1000;
  padding: 12px 20px;
  font-size: 14px;
  font-weight: 500;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transition: all var(--osr-transition-fast);

  &:active {
    transform: scale(0.96);
  }

  @media (min-width: 768px) {
    right: 40px;
    bottom: calc(56px + 24px);
    padding: 14px 24px;
    font-size: 15px;
  }
}
</style>
