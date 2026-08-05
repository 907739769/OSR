<template>
  <div class="mobile-page">
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
          @keyup.enter="handleQuery"
        />
        <v-text-field
          v-model="queryParams.sysUserName"
          label="OSR 登录名"
          placeholder="支持模糊匹配"
          clearable
          density="compact"
          variant="outlined"
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
        />
      </v-form>
    </MobileSearchPanel>

    <!-- 同步菜单 -->
    <div class="drawer-actions">
      <v-btn
        color="primary"
        variant="outlined"
        block
        prepend-icon="mdi-menu"
        :loading="syncingMenu"
        @click="handleSyncMenu"
      >
        同步应用菜单
      </v-btn>
    </div>

    <!-- 新增 FAB -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增企微绑定')">
      新增
    </v-btn>

    <!-- 列表 -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card v-for="item in taskList" :key="item.id" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <div class="card-title-row">
              <v-icon class="card-title-icon" icon="mdi-wechat" size="16" />
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
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '编辑企微绑定')">
              编辑
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
      </v-card>

      <v-empty-state
        v-if="!loading && taskList.length === 0"
        icon="mdi-account-off-outline"
        title="暂无绑定"
        text="在企微后台通讯录查到成员 UserId 后来这里绑定"
      />
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
              v-model="form.wecomUserid"
              label="企业微信 UserId"
              placeholder="企微后台通讯录成员详情页可查"
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
              :items="[{ title: '正常', value: '0' }, { title: '停用', value: '1' }]"
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
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useWecomUser } from '@/composables/useWecomUser'

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  open, dialogTitle, submitLoading, formRef, form, rules, userOptions,
  handleAdd, handleUpdate, submitForm, handleDelete,
  syncingMenu, handleSyncMenu,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = useWecomUser()

// 表单规则是 { 字段名: [{ required, message, trigger }] } 的普通对象（composable 返回），
// Vuetify 的 :rules 需要函数格式，这里就地转换，不改动 composable
const toRuleFns = (ruleList: any[]) =>
  (ruleList || []).map((rule: any) => (value: any) => {
    if (rule.required && (value === null || value === undefined || value === '')) {
      return rule.message || '不能为空'
    }
    return true
  })

const wecomUserIdRules = toRuleFns(rules.wecomUserid)
const sysUserIdRules = toRuleFns(rules.sysUserId)

const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}
</script>
