<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="mdi-inbox-outline"
    empty-title="暂无媒体服务器"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
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
          :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
          label="状态"
          placeholder="全部状态"
          clearable
          density="compact"
          variant="outlined"
          hide-details
        />
      </MobileSearchPanel>

      <!-- 批量操作 -->
      <MobileBatchBar
        :visible="selectedIds.length > 0"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="clearSelection"
      >
        <v-btn variant="text" color="error" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的媒体服务器？`)">
          批量删除
        </v-btn>
      </MobileBatchBar>

      <!-- 新增 FAB -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增媒体服务器')">
        新增
      </v-btn>

      <!-- 列表 -->
    </template>

    <v-card
      v-for="item in taskList"
      :key="item.id"
      class="task-card"
      :class="{ selected: selectedIds.includes(item.id) }"
      @click="handleCardClick($event, item.id)"
    >
      <div class="card-checkbox">
        <v-checkbox-btn
          :model-value="selectedIds.includes(item.id)"
          density="compact"
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
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改媒体服务器')">修改</v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">删除</v-btn>
        </div>
      </div>
    </v-card>

    <template #foot>
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
                :rules="apiKeyRules"
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
            <v-btn :loading="testLoading" variant="outlined" @click="handleTest">测试连接</v-btn>
            <v-spacer />
            <v-btn variant="outlined" @click="open = false">取消</v-btn>
            <v-btn color="primary" variant="flat" :loading="submitLoading" @click="submitForm">确定</v-btn>
          </v-card-actions>
        </v-card>
      </v-dialog>
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import { computed } from 'vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtMediaServer } from '@/composables/usePtMediaServer'

const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePtMediaServer()

// 将对象格式的校验规则（composable 返回）转换为 Vuetify 的规则函数数组
const toRules = (fieldRules?: any[]) => {
  return (fieldRules || []).map((r: any) => (value: any) => {
    if (r.required && (value === undefined || value === null || value === '')) return r.message
    if (r.pattern && value && !r.pattern.test(value)) return r.message
    return true
  })
}

// 编辑时后端出于安全考虑会把 apiKey 脱敏为空（留空提交 = 沿用已保存值），
// 只有新增时才要求必填，否则编辑弹窗永远校验不过
const apiKeyRules = computed(() => (form.value.id ? [] : toRules(rules.apiKey)))
</script>
