<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <el-form ref="queryRef" :model="queryParams" label-width="72px">
        <el-form-item label="类型" prop="type">
          <el-select v-model="queryParams.type" placeholder="全部类型" clearable style="width: 100%">
            <el-option label="种子(GUID)" value="GUID" />
            <el-option label="发布组" value="RELEASE_GROUP" />
          </el-select>
        </el-form-item>
        <el-form-item label="展示内容" prop="displayValue">
          <el-input v-model="queryParams.displayValue" placeholder="标题或发布组名" clearable @keyup.enter="handleQuery" />
        </el-form-item>
      </el-form>
    </MobileSearchPanel>

    <!-- 新增 FAB -->
    <el-button class="fab-add" type="primary" size="large" round @click="handleAdd('新增发布组黑名单')">
      <el-icon><Plus /></el-icon> 新增
    </el-button>

    <!-- 列表 -->
    <div class="task-list" v-loading="loading">
      <div v-for="item in taskList" :key="item.id" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <span class="task-name" :title="item.displayValue">{{ item.displayValue || '(无展示内容)' }}</span>
            <el-tag :type="item.type === 'GUID' ? 'danger' : 'warning'" size="small" effect="light">
              {{ item.type === 'GUID' ? '种子' : '发布组' }}
            </el-tag>
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
          <el-button link type="danger" size="small" :icon="Delete" @click="handleDelete(item)">
            删除
          </el-button>
        </div>
      </div>

      <el-empty v-if="!loading && taskList.length === 0" description="暂无黑名单规则" />
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
    <el-dialog v-model="open" :title="dialogTitle" width="90%" append-to-body class="modern-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="发布组名" prop="value">
          <el-input v-model="form.value" placeholder="如 CHDWEB，大小写不敏感" />
        </el-form-item>
        <el-form-item label="原因" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="可选，如“转码质量差”" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
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

:deep(.modern-dialog) {
  .el-dialog__body {
    padding: 16px;
  }
}
</style>
