<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <el-form ref="queryRef" :model="queryParams" label-width="72px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="queryParams.name" placeholder="请输入名称" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态" prop="enabled">
          <el-select v-model="queryParams.enabled" placeholder="全部状态" clearable style="width: 100%">
            <el-option label="启用" value="1" />
            <el-option label="停用" value="0" />
          </el-select>
        </el-form-item>
      </el-form>
    </MobileSearchPanel>

    <!-- 批量操作 -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <el-button link type="danger" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的媒体服务器？`)">
        <el-icon><Delete /></el-icon> 批量删除
      </el-button>
      <el-button link size="small" @click="clearSelection">取消</el-button>
    </div>

    <!-- 新增 FAB -->
    <el-button class="fab-add" type="primary" size="large" round @click="handleAdd('新增媒体服务器')">
      <el-icon><Plus /></el-icon> 新增
    </el-button>

    <!-- 列表 -->
    <div class="task-list" v-loading="loading">
      <div
        v-for="item in taskList"
        :key="item.id"
        class="task-card"
        :class="{ selected: selectedIds.includes(item.id) }"
        @click="handleCardClick($event, item.id)"
      >
        <div class="card-checkbox">
          <el-checkbox
            :model-value="selectedIds.includes(item.id)"
            size="large"
            @change="toggleSelect(item.id)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <span class="task-name">{{ item.name }}</span>
            <el-tag :type="item.enabled === '1' ? 'success' : 'danger'" size="small" effect="light">
              {{ item.enabled === '1' ? '启用' : '停用' }}
            </el-tag>
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
          <el-button link type="primary" size="small" :icon="Edit" @click="handleUpdate(item, '修改媒体服务器')">
            修改
          </el-button>
          <el-button link type="danger" size="small" :icon="Delete" @click="handleDelete(item)">
            删除
          </el-button>
        </div>
      </div>

      <el-empty v-if="!loading && taskList.length === 0" description="暂无媒体服务器" />
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
    <el-dialog v-model="open" :title="dialogTitle" width="90%" append-to-body class="modern-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入名称" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="Emby" value="EMBY" />
            <el-option label="Jellyfin" value="JELLYFIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务器地址" prop="url">
          <el-input v-model="form.url" placeholder="如 http://192.168.1.10:8096" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="form.id ? '留空则不修改 API Key' : '请输入 API Key'"
          />
        </el-form-item>
        <el-form-item label="用户ID" prop="userId">
          <el-input v-model="form.userId" placeholder="留空则按服务器全库查询" />
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
import { Edit, Delete, Plus } from '@element-plus/icons-vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtMediaServer } from '@/composables/usePtMediaServer'

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePtMediaServer()
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

  .el-button {
    font-size: 12px;
    padding: 0 4px;
    height: auto;
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
    gap: 4px;
    flex-shrink: 0;
    padding-left: 8px;
    border-left: 1px solid var(--osr-border-light);

    .el-button {
      font-size: 11px;
      padding: 2px 4px;
      height: auto;
      white-space: nowrap;
    }
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

:deep(.modern-dialog) {
  .el-dialog__body {
    padding: 16px;
  }
}
</style>
