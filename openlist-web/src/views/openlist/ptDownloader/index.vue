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
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd('新增下载器')">
            新增
          </v-btn>
          <v-btn color="success" prepend-icon="mdi-pencil-outline" :disabled="single" @click="handleUpdate(undefined, '修改下载器')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的下载器？`)">
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
              <span class="label">类型</span>
              <span class="value">{{ downloaderTypeLabel(item.type) }}</span>
            </div>
            <div class="card-row">
              <span class="label">地址</span>
              <span class="value">{{ (item.useHttps === '1' ? 'https://' : 'http://') + item.host + ':' + item.port }}</span>
            </div>
            <div class="card-row">
              <span class="label">保存路径</span>
              <span class="value" :title="item.savePath">{{ item.savePath }}</span>
            </div>
            <div class="card-row">
              <span class="label">标签</span>
              <span class="value">{{ item.tag }}</span>
            </div>
            <div class="card-row">
              <span class="label">最大并发</span>
              <span class="value">{{ item.maxConcurrent ? item.maxConcurrent : '不限' }}</span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改下载器')">
              修改
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无下载器" />
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
            <v-select
              v-model="form.type"
              label="类型"
              :items="[{ title: 'qBittorrent', value: 'QBITTORRENT' }, { title: 'Transmission', value: 'TRANSMISSION' }]"
              density="comfortable"
              variant="outlined"
            />
            <v-text-field
              v-model="form.host"
              label="主机"
              placeholder="主机名或 IP，不含协议与端口"
              density="comfortable"
              variant="outlined"
              :rules="hostRules"
            />
            <v-text-field
              v-model.number="form.port"
              label="端口"
              type="number"
              min="1"
              max="65535"
              density="comfortable"
              variant="outlined"
              :rules="portRules"
            />
            <div class="form-item">
              <label class="form-label">HTTPS</label>
              <v-radio-group v-model="form.useHttps" inline hide-details>
                <v-radio label="关闭" value="0" />
                <v-radio label="开启" value="1" />
              </v-radio-group>
            </div>
            <v-text-field
              v-model="form.username"
              label="用户名"
              placeholder="请输入用户名"
              density="comfortable"
              variant="outlined"
            />
            <v-text-field
              v-model="form.password"
              label="密码"
              type="password"
              :placeholder="form.id ? '留空则不修改密码' : '请输入密码'"
              density="comfortable"
              variant="outlined"
            />
            <div class="form-item">
              <v-text-field
                v-model="form.savePath"
                label="保存路径"
                placeholder="种子保存路径"
                density="comfortable"
                variant="outlined"
                :rules="savePathRules"
                @blur="handleSavePathBlur"
              />
              <div v-if="savePathWarning" class="save-path-warning">{{ savePathWarning }}</div>
            </div>
            <v-text-field
              v-model="form.tag"
              label="标签"
              placeholder="推送种子时打的标签"
              density="comfortable"
              variant="outlined"
              :rules="tagRules"
            />
            <div class="form-item">
              <v-text-field
                v-model.number="form.maxConcurrent"
                label="最大并发数"
                type="number"
                min="0"
                density="comfortable"
                variant="outlined"
                :rules="maxConcurrentRules"
              />
              <div class="field-hint">0 表示不限，达到上限时新任务会等到下一轮自动重试</div>
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
import { ref } from 'vue'
import { usePtDownloader } from '@/composables/usePtDownloader'

const showSearch = ref(window.innerWidth >= 768)

const DOWNLOADER_TYPE_LABELS: Record<string, string> = {
  QBITTORRENT: 'qBittorrent',
  TRANSMISSION: 'Transmission'
}
const downloaderTypeLabel = (type: string) => DOWNLOADER_TYPE_LABELS[type] || type

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  selectedIds, single, multiple, toggleSelect,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest, savePathWarning, handleSavePathBlur
} = usePtDownloader()

// Element Plus 表单规则是 { required, message, trigger }/{ type, min, max } 对象格式，
// Vuetify 的 v-text-field :rules 需要函数格式，这里就地转换，不改动 composable
const toRuleFns = (ruleList: any[]) =>
  (ruleList || []).map((rule: any) => (value: any) => {
    if (rule.required && (value === null || value === undefined || value === '')) {
      return rule.message || '不能为空'
    }
    if (rule.type === 'number' && value !== null && value !== undefined && value !== '') {
      if (rule.min !== undefined && Number(value) < rule.min) return rule.message || `不得小于 ${rule.min}`
      if (rule.max !== undefined && Number(value) > rule.max) return rule.message || `不得大于 ${rule.max}`
    }
    return true
  })

const nameRules = toRuleFns(rules.name)
const hostRules = toRuleFns(rules.host)
const portRules = toRuleFns(rules.port)
const savePathRules = toRuleFns(rules.savePath)
const tagRules = toRuleFns(rules.tag)
const maxConcurrentRules = toRuleFns(rules.maxConcurrent)

const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}
</script>

<style scoped lang="scss">
.save-path-warning {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: rgb(var(--v-theme-warning));
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--osr-text-secondary);
}

.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-card {
  padding: 14px 16px;
}

.search-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px;

  > .v-text-field,
  > .v-select {
    width: 220px;
    flex: 0 0 auto;
  }

  .status-select {
    width: 140px;
  }

  .search-actions {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-top: 2px;
  }
}

.table-card {
  padding: 16px;
  display: flex;
  flex-direction: column;
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

@media (max-width: 768px) {
  .page-container {
    gap: 10px;
  }

  .search-fields {
    > .v-text-field,
    > .v-select,
    .status-select {
      width: 100%;
    }

    .search-actions {
      width: 100%;

      .v-btn {
        flex: 1;
      }
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

  .table-card {
    padding: 12px;
  }

  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
