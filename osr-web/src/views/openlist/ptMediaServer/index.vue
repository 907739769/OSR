<template>
  <div class="page-container">
    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form @submit.prevent="handleQuery">
        <div class="search-row">
          <v-text-field
            v-model="queryParams.name"
            label="名称"
            placeholder="请输入名称"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            class="search-field"
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.enabled"
            :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
            label="状态"
            placeholder="状态"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            class="search-field search-field-sm"
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
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd('新增媒体服务器')">
            新增
          </v-btn>
          <v-btn color="success" prepend-icon="mdi-pencil-outline" :disabled="single" @click="handleUpdate(undefined, '修改媒体服务器')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的媒体服务器？`)">
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
              <v-checkbox-btn
                :model-value="selectedIds.includes(item.id)"
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
              <span class="label">类型</span>
              <span class="value">{{ item.type === 'JELLYFIN' ? 'Jellyfin' : 'Emby' }}</span>
            </div>
            <div class="card-row">
              <span class="label">服务器地址</span>
              <span class="value" :title="item.url">{{ item.url }}</span>
            </div>
            <div class="card-row">
              <span class="label">创建时间</span>
              <span class="value">{{ item.createTime }}</span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改媒体服务器')">
              修改
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无媒体服务器" />
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
    <v-dialog v-model="open" max-width="600" class="modern-dialog">
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
import { ref } from 'vue'
import { usePtMediaServer } from '@/composables/usePtMediaServer'

const showSearch = ref(window.innerWidth >= 768)

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery,
  selectedIds, single, multiple, toggleSelect,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest
} = usePtMediaServer()

// 将对象格式的校验规则（composable 返回）转换为 Vuetify 的规则函数数组
const toRules = (fieldRules?: any[]) => {
  return (fieldRules || []).map((r: any) => (value: any) => {
    if (r.required && (value === undefined || value === null || value === '')) return r.message
    if (r.pattern && value && !r.pattern.test(value)) return r.message
    return true
  })
}
</script>

<style scoped lang="scss">

.search-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.search-field {
  width: 220px;
  flex: none;
}

.search-field-sm {
  width: 140px;
}

.search-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

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

@media (max-width: 768px) {

  .search-row {
    .search-field,
    .search-field-sm {
      width: 100%;
    }

    .search-actions {
      margin-left: 0;
      width: 100%;
    }
  }

  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
