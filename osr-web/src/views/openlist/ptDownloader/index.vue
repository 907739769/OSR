<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-download-network-outline"
      title="PT 下载器"
      desc="配置 qBittorrent / Transmission 连接与保存路径"
    />

    <!-- Search Panel -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
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
    </SearchPanel>

    <!-- Table Card -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd('新增下载器')">
            新增
          </v-btn>
          <v-btn color="success" prepend-icon="mdi-pencil-outline" :disabled="notOneSelected" @click="handleUpdate(undefined, '修改下载器')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="noneSelected" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的下载器？`)">
            批量删除
          </v-btn>
          <v-btn variant="text" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
            {{ isAllPageSelected ? '取消全选' : '全选' }}
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <div class="card-grid" ref="gridRef">
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
            <StatusChip :value="item.enabled" />
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
            <div class="card-row">
              <span class="label">智能分类</span>
              <span class="value">{{ smartClassifyLabel(item.smartClassifyLevel) }}</span>
            </div>
            <div class="card-row">
              <span class="label">分工</span>
              <span class="value">{{ roleLabel(item.role) }}</span>
            </div>
            <div class="card-row">
              <span class="label">自动删种</span>
              <span class="value">{{ item.autoDeleteEnabled === '1' ? '已开启' : '未开启' }}</span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改下载器')">
              修改
            </v-btn>
            <v-btn variant="text" size="small" prepend-icon="mdi-broom" @click="openCleanRules(item)">
              删种规则
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无下载器" />
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
            <FormField label="HTTPS">
              <v-radio-group v-model="form.useHttps" inline hide-details>
                <v-radio label="关闭" value="0" />
                <v-radio label="开启" value="1" />
              </v-radio-group>
            </FormField>
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
            <FormField>
              <v-text-field
                v-model="form.savePath"
                label="保存路径"
                placeholder="种子保存路径"
                density="comfortable"
                variant="outlined"
                :rules="savePathRules"
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
              density="comfortable"
              variant="outlined"
              :rules="tagRules"
            />
            <FormField tip="0 表示不限，达到上限时新任务会等到下一轮自动重试">
              <v-text-field
                v-model.number="form.maxConcurrent"
                label="最大并发数"
                type="number"
                min="0"
                density="comfortable"
                variant="outlined"
                :rules="maxConcurrentRules"
              />
            </FormField>
            <FormField label="状态">
              <v-radio-group v-model="form.enabled" inline hide-details>
                <v-radio label="启用" value="1" />
                <v-radio label="停用" value="0" />
              </v-radio-group>
            </FormField>
            <FormField tip="推送种子时按分类在保存路径下自动建子目录，同步到网盘的目录结构会一并跟随">
              <v-select
                v-model="form.smartClassifyLevel"
                label="智能分类"
                :items="SMART_CLASSIFY_LEVEL_OPTIONS"
                density="comfortable"
                variant="outlined"
              />
            </FormField>
            <FormField tip="「仅做种」的下载器不参与订阅下载的负载均衡，用于接收 IYUU 转移/辅种过来的种子">
              <v-select
                v-model="form.role"
                label="分工"
                :items="ROLE_OPTIONS"
                density="comfortable"
                variant="outlined"
              />
            </FormField>
            <FormField
              label="自动删种"
              tip="按「删种规则」定期清理已达标的种子。仍在 H&R 考核中的种子永远不删；辅种整组同删。开启后请先用规则弹窗里的「预览」确认判定结果"
            >
              <v-radio-group v-model="form.autoDeleteEnabled" inline hide-details>
                <v-radio label="关闭" value="0" />
                <v-radio label="开启" value="1" />
              </v-radio-group>
            </FormField>
            <template v-if="form.autoDeleteEnabled === '1'">
              <FormField tip="逗号分隔。带其中任一标签的种子及其辅种组永不删除">
                <v-text-field
                  v-model="form.autoDeleteExcludeTags"
                  label="删种排除标签"
                  placeholder="如：keep,手动保留"
                  density="comfortable"
                  variant="outlined"
                />
              </FormField>
              <FormField tip="0 表示不限。规则配错时最多损失一轮的量，不会一次清空整个保种盘">
                <v-text-field
                  v-model.number="form.autoDeleteMaxPerRound"
                  label="单轮最多删除组数"
                  type="number"
                  min="0"
                  density="comfortable"
                  variant="outlined"
                />
              </FormField>
            </template>
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

    <!-- 自动删种规则 -->
    <PtCleanRuleDialog v-model="cleanRuleOpen" :downloader="cleanRuleTarget" />
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import FormField from '@/components/FormField.vue'
import PtCleanRuleDialog from '@/components/PtCleanRuleDialog.vue'
import { usePtDownloader } from '@/composables/usePtDownloader'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'

const { showSearch } = useSearchPanel()

const DOWNLOADER_TYPE_LABELS: Record<string, string> = {
  QBITTORRENT: 'qBittorrent',
  TRANSMISSION: 'Transmission'
}
const downloaderTypeLabel = (type: string) => DOWNLOADER_TYPE_LABELS[type] || type

const SMART_CLASSIFY_LEVEL_OPTIONS = [
  { title: '不分类', value: 'NONE' },
  { title: '按类型分类（电影/剧集）', value: 'CATEGORY' },
  { title: '按类型+首播年份分类', value: 'CATEGORY_YEAR' }
]
const smartClassifyLabel = (value: string) =>
  SMART_CLASSIFY_LEVEL_OPTIONS.find(o => o.value === value)?.title || '不分类'

const ROLE_OPTIONS = [
  { title: '订阅下载', value: 'DOWNLOAD' },
  { title: '仅做种（不接订阅）', value: 'SEED_ONLY' }
]
// role 是后加的列，存量下载器为空，按「订阅下载」显示——与后端的退化口径一致
const roleLabel = (value: string) =>
  ROLE_OPTIONS.find(o => o.value === value)?.title || '订阅下载'

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  selectedIds, notOneSelected, noneSelected, toggleSelect,
  isAllPageSelected, toggleSelectAllPage,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest, savePathWarning, handleSavePathBlur,
  cleanRuleOpen, cleanRuleTarget, openCleanRules
} = usePtDownloader({ autoLoad: false })

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})

// 表单规则是 { required, message, trigger }/{ type, min, max } 对象格式（composable 返回），
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
/* .card-grid / .item-card / .card-header / .card-body / .card-row / .card-footer
   已统一由 styles/list.scss 提供，本页不再重复定义 */

/* 保存路径的风险提示用警告色，区别于普通说明文字 */
.save-path-warning {
  color: rgb(var(--v-theme-warning));
}
</style>
