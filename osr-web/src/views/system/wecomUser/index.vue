<template>
  <div class="page-container">
    <PageHeader
      icon="brand-wecom"
      title="企业微信用户"
      desc="企业微信成员与 OSR 账号的绑定关系 — 绑定后该成员才能在企微里订阅，其订阅通知也只推给他"
    >
      <template #actions>
        <v-btn
          color="primary"
          variant="outlined"
          prepend-icon="menu"
          :loading="syncingMenu"
          @click="handleSyncMenu"
        >
          同步应用菜单
        </v-btn>
      </template>
    </PageHeader>

    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.wecomUserid"
        label="企微 UserId"
        placeholder="支持模糊匹配"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="field-md"
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
        class="field-md"
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
        class="field-sm"
      />
    </SearchPanel>

    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd('新增企微绑定')">新增绑定</v-btn>
        </div>
        <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <div class="card-grid" ref="gridRef">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <div v-for="item in taskList" :key="item.id" class="item-card">
          <div class="card-header">
            <span class="card-title" :title="item.wecomUserid">{{ item.wecomUserid }}</span>
            <StatusChip :type="item.status === '1' ? 'error' : 'success'" :text="item.status === '1' ? '停用' : '正常'" />
          </div>
          <div class="card-body">
            <div class="card-row">
              <span class="label">绑定用户</span>
              <span class="value">{{ item.sysUserName || `#${item.sysUserId}` }}</span>
            </div>
            <div class="card-row">
              <span class="label">备注</span>
              <span class="value">{{ item.remark || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">创建时间</span>
              <span class="value">{{ item.createTime || '-' }}</span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item, '编辑企微绑定')">
              编辑
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state
          v-if="!loading && taskList.length === 0"
          icon="user-round-x"
          title="暂无绑定"
          text="在企微管理后台「通讯录」里查到成员的 UserId，再来这里绑定 OSR 账号"
        />
      </div>

      <div class="pagination-wrapper">
        <span class="total-text">共 {{ total }} 条</span>
        <v-select
          :model-value="queryParams.pageSize"
          :items="pageSizeOptions"
          density="compact"
          variant="outlined"
          hide-details
          class="page-size-select"
          @update:model-value="setPageSize"
        />
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize!) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>

    <v-dialog v-model="open" max-width="600">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field
              v-model="form.wecomUserid"
              label="企业微信 UserId"
              placeholder="企微管理后台「通讯录」成员详情页可查"
              :rules="wecomUserIdRules"
              class="mb-3"
            />
            <v-select
              v-model="form.sysUserId"
              label="绑定到 OSR 用户"
              :items="userOptions"
              :rules="sysUserIdRules"
              placeholder="请选择"
              class="mb-3"
            />
            <v-select
              v-model="form.status"
              label="状态"
              :items="[{ title: '正常', value: '0' }, { title: '停用（拒绝指令、不再定向推送）', value: '1' }]"
              class="mb-3"
            />
            <v-textarea v-model="form.remark" label="备注" rows="2" placeholder="可选" />
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="open = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="handleSubmitClick">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useWecomUser } from '@/composables/useWecomUser'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'

const { showSearch } = useSearchPanel()

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  open, dialogTitle, submitLoading, formRef, form, rules, userOptions,
  handleAdd, handleUpdate, submitForm, handleDelete,
  syncingMenu, handleSyncMenu
} = useWecomUser({ autoLoad: false })

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})

// 表单规则是 { required, message, trigger } 对象格式（composable 返回），
// Vuetify 的 :rules 需要函数格式，这里就地转换，不改动 composable
const toRuleFns = (ruleList: any[]) =>
  (ruleList || []).map((rule: any) => (value: any) => {
    if (rule.required && (value === null || value === undefined || value === '')) {
      return rule.message || '不能为空'
    }
    return true
  })

// rules 是 { 字段名: 规则数组 } 的普通对象（不是 ref），按字段名取
const wecomUserIdRules = toRuleFns(rules.wecomUserid)
const sysUserIdRules = toRuleFns(rules.sysUserId)

const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}
</script>
