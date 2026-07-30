<template>
  <div class="page-container">
    <!-- Search Panel -->
    <el-card class="search-card" v-if="showSearch">
      <el-form :model="queryParams" ref="queryRef" :inline="true" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="queryParams.name" placeholder="请输入名称" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态" prop="enabled">
          <el-select v-model="queryParams.enabled" placeholder="状态" clearable :style="{ width: '120px' }">
            <el-option label="启用" value="1" />
            <el-option label="停用" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="resetQuery">
            <el-icon><Refresh /></el-icon> 重置
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Table Card -->
    <el-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <el-button type="primary" @click="handleAdd('新增索引器')">
            <el-icon><Plus /></el-icon> 新增
          </el-button>
          <el-button type="success" :disabled="single" @click="handleUpdate(undefined, '修改索引器')">
            <el-icon><Edit /></el-icon> 修改
          </el-button>
          <el-button type="danger" :disabled="multiple" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的索引器？`)">
            <el-icon><Delete /></el-icon> 批量删除
          </el-button>
        </div>
        <el-button text @click="showSearch = !showSearch">
          <el-icon><Filter /></el-icon>
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </el-button>
      </div>

      <div class="card-grid" v-loading="loading">
        <div v-for="item in taskList" :key="item.id" class="item-card">
          <div class="card-header">
            <div class="card-checkbox">
              <el-checkbox
                :model-value="selectedIds.includes(item.id)"
                @change="toggleSelect(item.id)"
              />
            </div>
            <span class="card-title" :title="item.name">{{ item.name }}</span>
            <el-tag :type="item.enabled === '1' ? 'success' : 'danger'" size="small">
              {{ item.enabled === '1' ? '启用' : '停用' }}
            </el-tag>
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
                <el-tag v-else-if="item.lastStatus === 'OK'" type="success" size="small">正常</el-tag>
                <el-tag v-else type="danger" size="small">{{ item.lastStatus }}</el-tag>
              </span>
            </div>
            <div class="card-row" v-if="item.failCount > 0">
              <span class="label">连续失败</span>
              <span class="value">
                <el-tag :type="item.failCount >= 10 ? 'danger' : 'warning'" size="small">
                  {{ item.failCount }} 次
                </el-tag>
              </span>
            </div>
          </div>
          <div class="card-footer">
            <el-button link type="primary" @click="handleUpdate(item, '修改索引器')">
              <el-icon><Edit /></el-icon> 修改
            </el-button>
            <el-button link type="danger" @click="handleDelete(item)">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </div>
        </div>
        <el-empty v-if="!loading && taskList.length === 0" description="暂无索引器" />
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="getList"
          @size-change="getList"
        />
      </div>
    </el-card>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="open" :title="dialogTitle" width="600px" append-to-body class="modern-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入名称" />
        </el-form-item>
        <el-form-item label="接口地址" prop="url">
          <el-input v-model="form.url" placeholder="如 http://jackett:9117/api/v2.0/indexers/xxx/results/torznab/api" />
        </el-form-item>
        <el-form-item label="apikey" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="form.id ? '留空则不修改 apikey' : '请输入 Torznab apikey'"
          />
        </el-form-item>
        <el-form-item label="分类" prop="categories">
          <div class="category-field">
            <el-select
              v-model="categoriesSelected"
              multiple
              filterable
              allow-create
              default-first-option
              collapse-tags
              collapse-tags-tooltip
              placeholder="点击右侧「获取分类」后选择，或直接输入分类 ID"
            >
              <el-option-group
                v-for="parent in categoryOptions"
                :key="parent.id"
                :label="`${parent.name} (${parent.id})`"
              >
                <el-option :label="`${parent.name} (${parent.id})`" :value="String(parent.id)" />
                <el-option
                  v-for="child in parent.children"
                  :key="child.id"
                  :label="`\u3000${child.name} (${child.id})`"
                  :value="String(child.id)"
                />
              </el-option-group>
            </el-select>
            <el-button :loading="categoriesLoading" @click="fetchCategories">获取分类</el-button>
          </div>
        </el-form-item>
        <el-form-item label="轮询周期" prop="pollInterval">
          <el-input-number v-model="form.pollInterval" :min="60" :step="60" :style="{ width: '200px' }" />
          <span class="form-tip">秒</span>
        </el-form-item>
        <el-form-item label="状态" prop="enabled">
          <el-radio-group v-model="form.enabled">
            <el-radio value="1">启用</el-radio>
            <el-radio value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="testLoading" @click="handleTest">测试连接</el-button>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
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
</script>

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);

  :deep(.el-card__body) {
    padding: 14px 16px;
  }
}

.table-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);

  :deep(.el-card__body) {
    padding: 16px;
    display: flex;
    flex-direction: column;
  }
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;

  .action-left {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 12px;
}

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

.category-field {
  display: flex;
  gap: 8px;
  align-items: flex-start;

  .el-select {
    flex: 1;
    min-width: 0;
  }
}

@media (max-width: 768px) {
  .page-container {
    gap: 10px;
  }

  .search-card :deep(.el-form) {
    .el-form-item {
      margin-right: 0;
    }

    .el-input,
    .el-select {
      width: 100% !important;
    }
  }

  .action-bar {
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 10px;

    .action-left {
      gap: 4px;
    }
  }

  .table-card :deep(.el-card__body) {
    padding: 12px;
  }

  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
