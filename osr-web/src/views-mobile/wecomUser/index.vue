<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="user-round-x"
    empty-title="暂无绑定"
    empty-text="在企微后台通讯录查到成员 UserId 后来这里绑定"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
        <v-form ref="queryRef">
          <v-text-field
            v-model="queryParams.wecomUserid"
            label="企微 UserId"
            placeholder="支持模糊匹配"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.sysUserName"
            label="OSR 登录名"
            placeholder="支持模糊匹配"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.status"
            label="状态"
            :items="[{ title: '正常', value: '0' }, { title: '停用', value: '1' }]"
            placeholder="全部状态"
            clearable
            density="compact"
            variant="outlined"
            hide-details
          />
        </v-form>
      </MobileSearchPanel>

      <!-- 同步菜单 -->
      <div class="drawer-actions">
        <v-btn
          color="primary"
          variant="outlined"
          block
          prepend-icon="menu"
          :loading="syncingMenu"
          @click="handleSyncMenu"
        >
          同步应用菜单
        </v-btn>
      </div>

      <!-- 新增 FAB -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd('新增企微绑定')">
        新增
      </v-btn>

      <!-- 列表 -->
    </template>

    <v-card v-for="item in taskList" :key="item.id" class="task-card">
      <div class="card-content">
        <div class="card-top">
          <div class="card-title-row">
            <v-icon class="card-title-icon" icon="brand-wecom" size="16" />
            <span class="card-title" :title="item.wecomUserid">{{ item.wecomUserid }}</span>
          </div>
          <StatusChip :type="item.status === '1' ? 'error' : 'success'" :text="item.status === '1' ? '停用' : '正常'" />
        </div>
        <div class="card-detail">
          <div class="detail-row">
            <span class="label">绑定用户</span>
            <span class="value">{{ item.sysUserName || `#${item.sysUserId}` }}</span>
          </div>
          <div class="detail-row">
            <span class="label">备注</span>
            <span class="value">{{ item.remark || '-' }}</span>
          </div>
        </div>
        <div class="card-time">{{ item.createTime || '-' }}</div>
        <div class="card-actions">
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item, '编辑企微绑定')">
            编辑
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
            删除
          </v-btn>
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

      <!-- 新增/编辑弹窗（两端共用） -->
      <WecomUserFormDialog />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useWecomUser } from '@/composables/useWecomUser'
import { usePageStateProvider } from '@/composables/pageStateContext'
import WecomUserFormDialog from '@/components/dialogs/WecomUserFormDialog.vue'

// 表单弹窗与 PC 端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  handleAdd, handleUpdate, handleDelete,
  syncingMenu, handleSyncMenu,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePageStateProvider(useWecomUser())
</script>
