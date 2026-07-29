<template>
  <div class="mobile-page">
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <el-form ref="queryRef" :model="queryParams" label-width="72px">
        <el-form-item label="规则名称" prop="name">
          <el-input v-model="queryParams.name" placeholder="规则名称" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="媒体类型" prop="mediaType">
          <el-select v-model="queryParams.mediaType" placeholder="全部类型" clearable style="width: 100%">
            <el-option label="电影" value="MOVIE" />
            <el-option label="剧集" value="TV" />
          </el-select>
        </el-form-item>
      </el-form>
    </MobileSearchPanel>

    <el-button class="fab-add" type="primary" size="large" round @click="handleAdd('新增热门自动订阅规则')">
      <el-icon><Plus /></el-icon> 新增
    </el-button>

    <div class="task-list" v-loading="loading">
      <div v-for="item in taskList" :key="item.id" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <span class="task-name" :title="item.name">{{ item.name }}</span>
            <el-tag :type="item.enabled === '1' ? 'success' : 'info'" size="small" effect="light">
              {{ item.enabled === '1' ? '启用' : '停用' }}
            </el-tag>
          </div>
          <div class="card-detail">
            <div class="detail-row">
              <span class="label">类型</span>
              <span class="value">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }} · {{ sourceLabel(item.source) }}</span>
            </div>
            <div class="detail-row">
              <span class="label">单轮/间隔</span>
              <span class="value">{{ item.maxAddPerRun }}部 / {{ item.intervalHours }}h</span>
            </div>
            <div class="detail-row">
              <span class="label">上次执行</span>
              <span class="value">{{ item.lastRunTime || '未执行' }}</span>
            </div>
          </div>
        </div>
        <div class="card-actions">
          <el-button link type="primary" size="small" :loading="runningIds.has(item.id)" @click="handleRun(item)">执行</el-button>
          <el-button link type="primary" size="small" @click="handleShowLogs(item)">日志</el-button>
          <el-button link type="primary" size="small" @click="handleUpdate(item, '编辑规则')">编辑</el-button>
          <el-button link type="danger" size="small" :icon="Delete" @click="handleDelete(item)">删除</el-button>
        </div>
      </div>

      <el-empty v-if="!loading && taskList.length === 0" description="暂无规则" />
    </div>

    <MobilePager
      v-model:page-size="queryParams.pageSize"
      :page-num="queryParams.pageNum"
      :total="total"
      :total-pages="totalPages"
      @prev="prevPage"
      @next="nextPage"
      @size-change="handleSizeChange"
    />

    <el-dialog v-model="open" :title="dialogTitle" width="90%" append-to-body class="modern-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="规则名称" prop="name">
          <el-input v-model="form.name" placeholder="如：每周热门电影" />
        </el-form-item>
        <el-form-item label="是否启用" prop="enabled">
          <el-switch v-model="form.enabled" active-value="1" inactive-value="0" />
        </el-form-item>
        <el-form-item label="媒体类型" prop="mediaType">
          <el-radio-group v-model="form.mediaType">
            <el-radio value="MOVIE">电影</el-radio>
            <el-radio value="TV">剧集</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数据源" prop="source">
          <el-select v-model="form.source" style="width: 100%">
            <el-option label="TMDb 每日热门" value="TMDB_TRENDING_DAY" />
            <el-option label="TMDb 每周热门" value="TMDB_TRENDING_WEEK" />
            <el-option label="TMDb 条件发现" value="TMDB_DISCOVER" />
          </el-select>
        </el-form-item>
        <el-form-item label="排除类型" prop="genreExclude">
          <el-select
            v-model="genreExcludeArr" multiple collapse-tags collapse-tags-tooltip clearable
            placeholder="不排除任何类型" style="width: 100%"
          >
            <el-option v-for="g in genreOptions" :key="g.id" :label="g.label" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="最低评分" prop="minVoteAverage">
          <el-input-number v-model="form.minVoteAverage" :min="0" :max="10" :step="0.5" style="width: 100%" />
        </el-form-item>
        <el-form-item label="最低人数" prop="minVoteCount">
          <el-input-number v-model="form.minVoteCount" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="地区" prop="region" v-if="form.source === 'TMDB_DISCOVER'">
          <el-select v-model="form.region" clearable placeholder="不限地区" style="width: 100%">
            <el-option v-for="r in REGION_OPTIONS" :key="r.code" :label="r.label" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="单轮上限" prop="maxAddPerRun">
          <el-input-number v-model="form.maxAddPerRun" :min="1" :max="50" style="width: 100%" />
        </el-form-item>
        <el-form-item label="执行间隔" prop="intervalHours">
          <el-input-number v-model="form.intervalHours" :min="1" :max="720" style="width: 100%" />
        </el-form-item>
        <el-form-item label="下载器" prop="downloaderId">
          <el-select v-model="form.downloaderId" clearable placeholder="空则用默认" style="width: 100%">
            <el-option v-for="d in downloaderOptions" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logDialogVisible" title="执行日志" width="90%" append-to-body class="modern-dialog">
      <div class="log-list" v-loading="logLoading">
        <div v-for="log in logList" :key="log.id" class="log-item">
          <div class="log-top">
            <span class="log-title" :title="log.title">{{ log.title || '-' }}</span>
            <el-tag :type="resultTagType(log.result)" size="small">{{ resultLabel(log.result) }}</el-tag>
          </div>
          <div class="log-meta">{{ log.createTime }}<span v-if="log.season"> · 第{{ log.season }}季</span></div>
          <div class="log-message" v-if="log.message">{{ log.message }}</div>
        </div>
        <el-empty v-if="!logLoading && logList.length === 0" description="暂无日志" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtAutoAddRule, REGION_OPTIONS } from '@/composables/usePtAutoAddRule'

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  runningIds, handleRun,
  logDialogVisible, logLoading, logList, handleShowLogs,
  genreOptions, genreExcludeArr, downloaderOptions
} = usePtAutoAddRule()

const sourceLabel = (source: string) => {
  const map: Record<string, string> = {
    TMDB_TRENDING_DAY: '每日热门',
    TMDB_TRENDING_WEEK: '每周热门',
    TMDB_DISCOVER: '条件发现'
  }
  return map[source] || source
}

const resultLabel = (result: string) => {
  const map: Record<string, string> = {
    ADDED: '已新增',
    SKIPPED_EXISTS: '已存在跳过',
    SKIPPED_FILTER: '过滤跳过',
    FAILED: '失败'
  }
  return map[result] || result
}

const resultTagType = (result: string): 'success' | 'info' | 'warning' | 'danger' => {
  const map: Record<string, 'success' | 'info' | 'warning' | 'danger'> = {
    ADDED: 'success',
    SKIPPED_EXISTS: 'info',
    SKIPPED_FILTER: 'warning',
    FAILED: 'danger'
  }
  return map[result] || 'info'
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
  flex-direction: column;
  gap: 8px;
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  padding: 12px;
  box-shadow: var(--osr-shadow-base);

  .card-content {
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
      width: 62px;
      color: var(--osr-text-secondary);
    }

    .value {
      flex: 1;
      min-width: 0;
      color: var(--osr-text-primary);
    }
  }

  .card-actions {
    display: flex;
    justify-content: flex-end;
    gap: 4px;
    padding-top: 6px;
    border-top: 1px solid var(--osr-border-light);
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

  @media (min-width: 768px) {
    right: 40px;
    bottom: calc(56px + 24px);
    padding: 14px 24px;
    font-size: 15px;
  }
}

.log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 60vh;
  overflow-y: auto;
}

.log-item {
  padding: 10px 12px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  display: flex;
  flex-direction: column;
  gap: 4px;

  .log-top {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;

    .log-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--osr-text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .log-meta {
    font-size: 12px;
    color: var(--osr-text-secondary);
  }

  .log-message {
    font-size: 12px;
    color: var(--osr-text-primary);
  }
}

:deep(.modern-dialog) {
  .el-dialog__body {
    padding: 16px;
  }
}
</style>
