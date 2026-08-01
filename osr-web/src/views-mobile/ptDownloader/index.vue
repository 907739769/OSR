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
      <v-btn variant="text" color="error" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的下载器？`)">
        批量删除
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">取消</v-btn>
    </div>

    <!-- 新增 FAB -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增下载器')">
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
              <span class="label">类型</span>
              <span class="value">{{ downloaderTypeLabel(item.type) }}</span>
            </div>
            <div class="detail-row">
              <span class="label">地址</span>
              <span class="value">{{ (item.useHttps === '1' ? 'https://' : 'http://') + item.host + ':' + item.port }}</span>
            </div>
            <div class="detail-row">
              <span class="label">保存路径</span>
              <span class="value">{{ item.savePath }}</span>
            </div>
            <div class="detail-row">
              <span class="label">标签</span>
              <span class="value">{{ item.tag }}</span>
            </div>
            <div class="detail-row">
              <span class="label">最大并发</span>
              <span class="value">{{ item.maxConcurrent ? item.maxConcurrent : '不限' }}</span>
            </div>
          </div>
          <div class="card-actions" @click.stop>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改下载器')">修改</v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">删除</v-btn>
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无下载器" />
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
            <v-select
              v-model="form.type"
              label="类型"
              :items="[{ title: 'qBittorrent', value: 'QBITTORRENT' }, { title: 'Transmission', value: 'TRANSMISSION' }]"
              class="mb-2"
            />
            <v-text-field
              v-model="form.host"
              label="主机"
              placeholder="主机名或 IP，不含协议与端口"
              :rules="toRules(rules.host)"
              class="mb-2"
            />
            <v-text-field
              v-model.number="form.port"
              label="端口"
              type="number"
              min="1"
              max="65535"
              :rules="toRules(rules.port)"
              class="mb-2"
            />
            <v-radio-group v-model="form.useHttps" inline label="HTTPS" hide-details class="mb-2">
              <v-radio label="关闭" value="0" />
              <v-radio label="开启" value="1" />
            </v-radio-group>
            <v-text-field
              v-model="form.username"
              label="用户名"
              placeholder="请输入用户名"
              class="mb-2"
            />
            <v-text-field
              v-model="form.password"
              label="密码"
              type="password"
              :placeholder="form.id ? '留空则不修改密码' : '请输入密码'"
              class="mb-2"
            />
            <FormField class="mb-2">
              <v-text-field
                v-model="form.savePath"
                label="保存路径"
                placeholder="种子保存路径"
                :rules="toRules(rules.savePath)"
                @blur="handleSavePathBlur"
              />
              <template v-if="savePathWarning" #tip>
                <span class="save-path-warning">{{ savePathWarning }}</span>
              </template>
            </FormField>
            <v-text-field
              v-model="form.tag"
              label="标签"
              placeholder="推送种子时打的标签"
              :rules="toRules(rules.tag)"
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
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="submitForm">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import FormField from '@/components/FormField.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtDownloader } from '@/composables/usePtDownloader'

const DOWNLOADER_TYPE_LABELS: Record<string, string> = {
  QBITTORRENT: 'qBittorrent',
  TRANSMISSION: 'Transmission'
}
const downloaderTypeLabel = (type: string) => DOWNLOADER_TYPE_LABELS[type] || type

const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest, savePathWarning, handleSavePathBlur,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePtDownloader()

// 将对象格式的校验规则（composable 返回）转换为 Vuetify 的规则函数数组
const toRules = (fieldRules?: any[]) => {
  return (fieldRules || []).map((r: any) => (value: any) => {
    if (r.required && (value === undefined || value === null || value === '')) return r.message
    if (r.type === 'number' && value !== null && value !== undefined && value !== '') {
      if (r.min !== undefined && Number(value) < r.min) return r.message
      if (r.max !== undefined && Number(value) > r.max) return r.message
    }
    return true
  })
}
</script>

<style scoped lang="scss">
.save-path-warning {
  color: rgb(var(--v-theme-warning));
}
</style>
