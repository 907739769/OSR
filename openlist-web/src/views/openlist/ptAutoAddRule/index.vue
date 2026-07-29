<template>
  <div class="page-container">
    <el-card class="search-card" v-if="showSearch">
      <el-form :model="queryParams" ref="queryRef" :inline="true" label-width="80px">
        <el-form-item label="规则名称" prop="name">
          <el-input v-model="queryParams.name" placeholder="规则名称" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="媒体类型" prop="mediaType">
          <el-select v-model="queryParams.mediaType" placeholder="全部类型" clearable :style="{ width: '140px' }">
            <el-option label="电影" value="MOVIE" />
            <el-option label="剧集" value="TV" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用状态" prop="enabled">
          <el-select v-model="queryParams.enabled" placeholder="全部" clearable :style="{ width: '120px' }">
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

    <el-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <el-button type="primary" @click="handleAdd('新增热门自动订阅规则')">
            <el-icon><Plus /></el-icon> 新增规则
          </el-button>
        </div>
        <el-button text @click="showSearch = !showSearch">
          <el-icon><Filter /></el-icon>
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </el-button>
      </div>

      <el-table v-loading="loading" :data="taskList" style="width: 100%">
        <el-table-column prop="name" label="规则名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="媒体类型" width="90">
          <template #default="{ row }">{{ row.mediaType === 'MOVIE' ? '电影' : '剧集' }}</template>
        </el-table-column>
        <el-table-column label="数据源" width="150">
          <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
        </el-table-column>
        <el-table-column label="过滤条件" min-width="180">
          <template #default="{ row }">
            <span v-if="row.minVoteAverage || row.minVoteCount || row.genreExclude">
              {{ row.minVoteAverage ? `评分≥${row.minVoteAverage} ` : '' }}
              {{ row.minVoteCount ? `评分人数≥${row.minVoteCount} ` : '' }}
              {{ row.genreExclude ? `排除类型:${row.genreExclude}` : '' }}
            </span>
            <span v-else class="text-muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="单轮上限" prop="maxAddPerRun" width="90" />
        <el-table-column label="执行间隔" width="100">
          <template #default="{ row }">{{ row.intervalHours }}h</template>
        </el-table-column>
        <el-table-column label="上次执行" prop="lastRunTime" width="160">
          <template #default="{ row }">{{ row.lastRunTime || '未执行' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === '1' ? 'success' : 'info'" size="small">
              {{ row.enabled === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="runningIds.has(row.id)" @click="handleRun(row)">立即执行</el-button>
            <el-button link type="primary" @click="handleShowLogs(row)">日志</el-button>
            <el-button link type="primary" @click="handleUpdate(row, '编辑规则')">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && taskList.length === 0" description="暂无规则" />

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

    <el-dialog v-model="open" :title="dialogTitle" width="560px" append-to-body class="modern-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
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
            <el-option label="TMDb 条件发现（按评分/地区）" value="TMDB_DISCOVER" />
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
          <el-input-number v-model="form.minVoteAverage" :min="0" :max="10" :step="0.5" placeholder="不限" />
        </el-form-item>
        <el-form-item label="最低评分人数" prop="minVoteCount">
          <el-input-number v-model="form.minVoteCount" :min="0" placeholder="不限" />
        </el-form-item>
        <el-form-item label="地区" prop="region" v-if="form.source === 'TMDB_DISCOVER'">
          <el-select v-model="form.region" clearable placeholder="不限地区" style="width: 100%">
            <el-option v-for="r in REGION_OPTIONS" :key="r.code" :label="r.label" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="单轮上限" prop="maxAddPerRun">
          <el-input-number v-model="form.maxAddPerRun" :min="1" :max="50" />
        </el-form-item>
        <el-form-item label="执行间隔" prop="intervalHours">
          <el-input-number v-model="form.intervalHours" :min="1" :max="720" />
          <span class="unit-hint">小时</span>
        </el-form-item>
        <el-form-item label="指定下载器" prop="downloaderId">
          <el-select v-model="form.downloaderId" clearable placeholder="空则用唯一启用的下载器" style="width: 100%">
            <el-option v-for="d in downloaderOptions" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logDialogVisible" title="执行日志" width="720px" append-to-body class="modern-dialog">
      <el-table v-loading="logLoading" :data="logList" max-height="480">
        <el-table-column prop="createTime" label="时间" width="160" />
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column label="季" width="60">
          <template #default="{ row }">{{ row.season ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="120">
          <template #default="{ row }">
            <el-tag :type="resultTagType(row.result)" size="small">{{ resultLabel(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="160" show-overflow-tooltip />
      </el-table>
      <el-empty v-if="!logLoading && logList.length === 0" description="暂无日志" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { usePtAutoAddRule, REGION_OPTIONS } from '@/composables/usePtAutoAddRule'

const showSearch = ref(window.innerWidth >= 768)

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  runningIds, handleRun,
  logDialogVisible, logLoading, logList, handleShowLogs,
  genreOptions, genreExcludeArr, downloaderOptions
} = usePtAutoAddRule()

const sourceLabel = (source: string) => {
  const map: Record<string, string> = {
    TMDB_TRENDING_DAY: 'TMDb 每日热门',
    TMDB_TRENDING_WEEK: 'TMDb 每周热门',
    TMDB_DISCOVER: 'TMDb 条件发现'
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
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.text-muted {
  color: var(--osr-text-secondary);
}

.unit-hint {
  margin-left: 8px;
  color: var(--osr-text-secondary);
  font-size: 13px;
}

@media (max-width: 768px) {
  .search-card :deep(.el-form) {
    .el-form-item {
      margin-right: 0;
    }

    .el-input,
    .el-select {
      width: 100% !important;
    }
  }

  .table-card :deep(.el-card__body) {
    padding: 12px;
  }
}
</style>
