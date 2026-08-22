<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无下载器"
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
        <v-btn variant="text" color="error" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的下载器？`)">
          批量删除
        </v-btn>
      </MobileBatchBar>

      <!-- 新增 FAB -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd('新增下载器')">
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
          <div class="detail-row">
            <span class="label">智能分类</span>
            <span class="value">{{ smartClassifyLabel(item.smartClassifyLevel) }}</span>
          </div>
          <div class="detail-row">
            <span class="label">分工</span>
            <span class="value">{{ roleLabel(item.role) }}</span>
          </div>
          <div class="detail-row">
            <span class="label">自动删种</span>
            <span class="value">{{ item.autoDeleteEnabled === '1' ? '已开启' : '未开启' }}</span>
          </div>
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item, '修改下载器')">修改</v-btn>
          <v-btn variant="text" size="small" prepend-icon="brush-cleaning" @click="openCleanRules(item)">删种规则</v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">删除</v-btn>
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
              <FormField class="mb-2" tip="0 表示不限，达到上限时新任务会等到下一轮自动重试">
                <v-text-field
                  v-model.number="form.maxConcurrent"
                  label="最大并发数"
                  type="number"
                  min="0"
                  :rules="toRules(rules.maxConcurrent)"
                />
              </FormField>
              <v-radio-group v-model="form.enabled" inline label="状态" hide-details class="mb-2">
                <v-radio label="启用" value="1" />
                <v-radio label="停用" value="0" />
              </v-radio-group>
              <FormField tip="推送种子时按分类在保存路径下自动建子目录，同步到网盘的目录结构会一并跟随">
                <v-select
                  v-model="form.smartClassifyLevel"
                  label="智能分类"
                  :items="SMART_CLASSIFY_LEVEL_OPTIONS"
                />
              </FormField>
              <FormField tip="「仅做种」的下载器不参与订阅下载的负载均衡，用于接收 IYUU 转移/辅种过来的种子">
                <v-select v-model="form.role" label="分工" :items="ROLE_OPTIONS" />
              </FormField>
              <FormField
                label="自动删种"
                tip="按「删种规则」定期清理已达标的种子。仍在 H&R 考核中的种子永远不删；辅种整组同删"
              >
                <v-radio-group v-model="form.autoDeleteEnabled" inline hide-details>
                  <v-radio label="关闭" value="0" />
                  <v-radio label="开启" value="1" />
                </v-radio-group>
              </FormField>
              <template v-if="form.autoDeleteEnabled === '1'">
                <FormField tip="逗号分隔。带其中任一标签的种子及其辅种组永不删除">
                  <v-text-field v-model="form.autoDeleteExcludeTags" label="删种排除标签" placeholder="如：keep,手动保留" />
                </FormField>
                <FormField tip="0 表示不限。规则配错时最多损失一轮的量">
                  <v-text-field v-model.number="form.autoDeleteMaxPerRound" label="单轮最多删除组数" type="number" min="0" />
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
      <PtCleanRuleDialog v-model="cleanRuleOpen" :downloader="cleanRuleTarget" mobile />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import FormField from '@/components/FormField.vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import PtCleanRuleDialog from '@/components/PtCleanRuleDialog.vue'
import { usePtDownloader } from '@/composables/usePtDownloader'

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
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  testLoading, handleTest, savePathWarning, handleSavePathBlur,
  cleanRuleOpen, cleanRuleTarget, openCleanRules,
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

/** 与 PC 端一致：先校验 v-form 再提交，防止端口范围等非法值直接落库 */
const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}
</script>

<style scoped lang="scss">
.save-path-warning {
  color: rgb(var(--v-theme-warning));
}
</style>
