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
    <div v-if="selectedIds.length > 0" class="batch-bar">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="error" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的媒体服务器？`)">
        批量删除
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">取消</v-btn>
    </div>

    <!-- 新增 FAB -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增媒体服务器')">
      新增
    </v-btn>

    <!-- 列表 -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <div
        v-for="item in taskList"
        :key="item.id"
        class="task-card"
        :class="{ selected: selectedIds.includes(item.id) }"
        @click="handleCardClick($event, item.id)"
      >
        <div class="card-checkbox">
          <v-checkbox-btn
            :model-value="selectedIds.includes(item.id)"
            @update:model-value="toggleSelect(item.id)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <span class="task-name">{{ item.name }}</span>
            <v-chip :color="item.enabled === '1' ? 'success' : 'error'" size="small" variant="tonal">
              {{ item.enabled === '1' ? '启用' : '停用' }}
            </v-chip>
          </div>
          <div class="card-detail">
            <div class="detail-row">
              <span class="label">类型</span>
              <span class="value">{{ item.type === 'JELLYFIN' ? 'Jellyfin' : 'Emby' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">服务器地址</span>
              <span class="value">{{ item.url }}</span>
            </div>
            <div class="detail-row">
              <span class="label">创建时间</span>
              <span class="value">{{ item.createTime }}</span>
            </div>
          </div>
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" icon="mdi-pencil-outline" @click="handleUpdate(item, '修改媒体服务器')" />
          <v-btn variant="text" color="error" size="small" icon="mdi-delete-outline" @click="handleDelete(item)" />
        </div>
      </div>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无媒体服务器" />
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
    <v-dialog v-model="open" width="90%" class="modern-dialog">
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
            <v-select
              v-model="form.type"
              label="类型"
              :items="[{ title: 'Emby', value: 'EMBY' }, { title: 'Jellyfin', value: 'JELLYFIN' }]"
              class="mb-2"
            />
            <v-text-field
              v-model="form.url"
              label="服务器地址"
              placeholder="如 http://192.168.1.10:8096"
              :rules="toRules(rules.url)"
              class="mb-2"
            />
            <v-text-field
              v-model="form.apiKey"
              label="API Key"
              type="password"
              :placeholder="form.id ? '留空则不修改 API Key' : '请输入 API Key'"
              :rules="toRules(rules.apiKey)"
              class="mb-2"
            />
            <v-text-field
              v-model="form.userId"
              label="用户ID"
              placeholder="留空则按服务器全库查询"
              class="mb-2"
            />
            <v-radio-group v-model="form.enabled" inline label="状态" hide-details>
              <v-radio label="启用" value="1" />
              <v-radio label="停用" value="0" />
            </v-radio-group>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-btn :loading="testLoading" @click="handleTest">测试连接</v-btn>
          <v-spacer />
          <v-btn @click="open = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="submitForm">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtMediaServer } from '@/composables/usePtMediaServer'

const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePtMediaServer()

// 将 Element Plus 风格的校验规则对象转换为 Vuetify 的规则函数数组
const toRules = (fieldRules?: any[]) => {
  return (fieldRules || []).map((r: any) => (value: any) => {
    if (r.required && (value === undefined || value === null || value === '')) return r.message
    if (r.pattern && value && !r.pattern.test(value)) return r.message
    return true
  })
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

.batch-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  background: var(--osr-primary-light-9);
  border: 1px solid var(--osr-primary-light-7);
  border-radius: var(--osr-radius-md);
  font-size: 13px;

  .selected-count {
    font-weight: 600;
    color: var(--osr-primary);
    margin-right: 4px;
    white-space: nowrap;
  }
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
  border: 2px solid transparent;
  transition: all var(--osr-transition-fast);

  &.selected {
    border-color: var(--osr-primary-light-5);
    background: var(--osr-primary-light-9);
  }

  &:active {
    transform: scale(0.99);
  }

  .card-checkbox {
    flex-shrink: 0;
    display: flex;
    align-items: flex-start;
    padding-top: 2px;
    padding-left: 2px;
  }

  .card-content {
    flex: 1;
    min-width: 0;
  }

  .card-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
    gap: 8px;

    .task-name {
      font-size: 14px;
      font-weight: 600;
      color: var(--osr-text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
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
      width: 74px;
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
    display: flex;
    align-items: center;
    gap: 2px;
    flex-shrink: 0;
    padding-left: 4px;
    border-left: 1px solid var(--osr-border-light);
  }
}

.fab-add {
  position: fixed;
  right: 20px;
  bottom: calc(56px + 16px + env(safe-area-inset-bottom, 0px));
  z-index: 1000;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transition: all var(--osr-transition-fast);

  &:active {
    transform: scale(0.96);
  }

  @media (min-width: 768px) {
    right: 40px;
    bottom: calc(56px + 24px);
  }
}
</style>
