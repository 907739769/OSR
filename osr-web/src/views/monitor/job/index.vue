<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-clock-outline"
      title="定时任务"
      desc="基于 Quartz 的调度任务，可手动执行并查看执行日志"
    />

    <!-- Search Panel -->
    <v-card class="search-card" v-if="showSearch">
      <v-card-text>
        <v-form ref="queryRef" @submit.prevent="handleQuery">
          <div class="search-fields">
            <v-text-field
              v-model="queryParams.jobName"
              label="任务名称"
              placeholder="请输入任务名称"
              clearable
              density="compact"
              variant="outlined"
              hide-details
              @keyup.enter="handleQuery"
            />
            <v-select
              v-model="queryParams.status"
              :items="[{ title: '正常', value: '0' }, { title: '暂停', value: '1' }]"
              label="任务状态"
              placeholder="任务状态"
              clearable
              density="compact"
              variant="outlined"
              hide-details
              class="field-sm"
            />
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
          </div>
        </v-form>
      </v-card-text>
    </v-card>

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Action Bar -->
      <div class="action-bar">
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <!-- Desktop Table -->
      <v-data-table
        v-if="appStore.device === 'desktop'"
        :loading="loading"
        :items="jobList"
        :headers="jobHeaders"
        :items-per-page="-1"
        hide-default-footer
        class="modern-table"
      >
        <template #item.status="{ item }">
          <v-switch
            v-model="item.status"
            true-value="0"
            false-value="1"
            color="primary"
            density="compact"
            hide-details
            @update:model-value="() => handleSwitchChange(item)"
          />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改定时任务')">
            修改
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-outline" @click="handleRun(item)">
            执行
          </v-btn>
          <v-btn variant="text" color="secondary" size="small" prepend-icon="mdi-format-list-bulleted" @click="handleViewLogs(item)">
            记录
          </v-btn>
        </template>
      </v-data-table>

      <!-- Mobile Card List -->
      <div v-if="appStore.device === 'mobile'" class="mobile-card-list">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <v-card v-for="item in jobList" :key="item.jobId" variant="outlined" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title"><v-icon icon="mdi-cog-outline" size="14" /> {{ item.jobName }}</span>
            <v-switch
              v-model="item.status"
              true-value="0"
              false-value="1"
              color="primary"
              density="compact"
              hide-details
              @update:model-value="() => handleSwitchChange(item)"
            />
          </div>
          <div class="mobile-card-body">
            <div class="mobile-card-row">
              <span class="mobile-card-label">Cron</span>
              <span class="mobile-card-value mobile-card-value-clip">{{ item.cronExpression }}</span>
            </div>
          </div>
          <div class="mobile-card-actions">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改定时任务')">
              修改
            </v-btn>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-outline" @click="handleRun(item)">
              执行
            </v-btn>
            <v-btn variant="text" color="secondary" size="small" prepend-icon="mdi-format-list-bulleted" @click="handleViewLogs(item)">
              记录
            </v-btn>
          </div>
        </v-card>
        <v-empty-state v-if="!loading && !jobList.length" icon="mdi-inbox-outline" title="暂无数据" />
      </div>

      <!-- Pagination -->
      <div v-if="appStore.device === 'desktop'" class="pagination-wrapper">
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
      <MobilePager
        v-if="appStore.device === 'mobile'"
        v-model:page-size="queryParams.pageSize"
        :page-num="queryParams.pageNum"
        :total="total"
        :total-pages="totalPages"
        @prev="prevPage"
        @next="nextPage"
        @size-change="handleSizeChange"
      />
    </v-card>

    <!-- Add/Edit Dialog -->
    <v-dialog v-model="open" max-width="600">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field
              v-model="form.jobName"
              label="任务名称"
              placeholder="请输入任务名称"
              variant="outlined"
              density="comfortable"
              class="mb-2"
              :rules="[(v: any) => !!v || '任务名称不能为空']"
            />
            <div class="cron-field">
              <v-text-field
                v-model="form.cronExpression"
                label="cron执行表达式"
                placeholder="请输入cron表达式"
                variant="outlined"
                density="comfortable"
                class="mb-2 cron-input"
                :rules="[(v: any) => !!v || 'Cron表达式不能为空']"
              />
              <v-btn variant="outlined" @click="showCronDialog = true">表达式说明</v-btn>
            </div>
            <v-radio-group v-model="form.status" label="状态" inline hide-details class="mb-2">
              <v-radio label="正常" value="0" />
              <v-radio label="暂停" value="1" />
            </v-radio-group>
            <v-textarea
              v-model="form.remark"
              label="备注"
              rows="3"
              placeholder="请输入内容"
              variant="outlined"
              density="comfortable"
            />
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="open = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="submitForm">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Cron Expression Dialog -->
    <v-dialog v-model="showCronDialog" max-width="480">
      <v-card title="Cron表达式说明">
        <v-card-text>
          <div class="cron-desc">
            <p><strong>秒 分 时 日 月 周 年(可选)</strong></p>
            <p>例：0 0 12 * * ? 每天12点运行</p>
            <p>例：0 15 10 ? * * 每天10:15运行</p>
            <p>例：0 0/5 * * * ? 每5分钟运行</p>
            <p>例：0 15 10 ? * MON-FRI 周一到周五10:15运行</p>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="showCronDialog = false">关 闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Job Log Dialog -->
    <v-dialog
      v-model="logOpen"
      :width="appStore.device === 'mobile' ? '92%' : '900px'"
      :fullscreen="appStore.device === 'mobile'"
      class="log-dialog"
    >
      <v-card :title="`${logTitle} - 执行记录`">
        <v-card-text>
          <!-- Mobile: Collapsible Search Panel -->
          <MobileSearchPanel
            v-if="appStore.device === 'mobile'"
            v-model:collapsed="logSearchCollapsed"
            :loading="logLoading"
            class="mb-3"
            @search="logQueryParams.pageNum = 1; getJobLogList()"
            @reset="resetLogQuery"
          >
            <v-select
              v-model="logQueryParams.status"
              :items="[{ title: '成功', value: '0' }, { title: '失败', value: '1' }]"
              label="执行状态"
              placeholder="全部状态"
              clearable
              density="compact"
              variant="outlined"
              hide-details
            />
          </MobileSearchPanel>

          <!-- Desktop: Inline Search -->
          <div v-else class="inline-fields log-search-form">
            <v-select
              v-model="logQueryParams.status"
              :items="[{ title: '成功', value: '0' }, { title: '失败', value: '1' }]"
              label="执行状态"
              placeholder="全部状态"
              clearable
              density="compact"
              variant="outlined"
              hide-details
            />
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="logQueryParams.pageNum = 1; getJobLogList()">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetLogQuery">重置</v-btn>
          </div>

          <!-- Desktop Table -->
          <v-data-table
            v-if="appStore.device === 'desktop'"
            :loading="logLoading"
            :items="logList"
            :headers="logHeaders"
            :items-per-page="-1"
            hide-default-footer
            class="modern-table log-table"
          >
            <template #item.status="{ item }">
              <StatusChip :type="item.status === '0' ? 'success' : 'error'" :text="item.status === '0' ? '成功' : '失败'" />
            </template>
            <template #item.duration="{ item }">
              <span v-if="item.startTime && item.endTime">
                {{ formatDuration(item.startTime, item.endTime) }}
              </span>
              <span v-else>-</span>
            </template>
            <template #item.startTime="{ item }">
              {{ formatTime(item.startTime) }}
            </template>
            <template #item.actions="{ item }">
              <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-eye-outline" @click="handleViewLogDetail(item)">
                详情
              </v-btn>
            </template>
          </v-data-table>

          <!-- Mobile Card List -->
          <div v-if="appStore.device === 'mobile'" class="mobile-card-list">
            <v-progress-linear v-if="logLoading" indeterminate color="primary" />
            <v-card
              v-for="item in logList"
              :key="item.jobLogId"
              variant="flat"
              class="mobile-card"
            >
              <div class="mobile-card-header">
                <div class="mobile-card-title-row">
                  <span class="mobile-card-title">
                    <v-icon icon="mdi-clock-outline" size="14" />
                    {{ item.jobName }}
                  </span>
                  <StatusChip :type="item.status === '0' ? 'success' : 'error'" :text="item.status === '0' ? '成功' : '失败'" />
                </div>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row">
                  <span class="mobile-card-label">调用目标</span>
                  <span class="mobile-card-value mobile-card-value-clip">{{ item.invokeTarget }}</span>
                </div>
                <div class="mobile-card-row">
                  <span class="mobile-card-label">日志信息</span>
                  <span class="mobile-card-value mobile-card-value-clip" @click.stop="handleViewLogDetail(item)">{{ item.jobMessage || '-' }}</span>
                </div>
                <div class="mobile-card-row">
                  <span class="mobile-card-label">开始时间</span>
                  <span class="mobile-card-value mobile-card-value-light">{{ formatTime(item.startTime) }}</span>
                </div>
                <div class="mobile-card-row" v-if="item.startTime && item.endTime">
                  <span class="mobile-card-label">耗时</span>
                  <span class="mobile-card-value mobile-card-value-light">{{ formatDuration(item.startTime, item.endTime) }}</span>
                </div>
              </div>
              <div class="mobile-card-actions">
                <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-eye-outline" @click="handleViewLogDetail(item)">
                  查看详情
                </v-btn>
              </div>
            </v-card>
            <v-empty-state v-if="!logLoading && !logList.length" icon="mdi-inbox-outline" title="暂无执行记录" />
          </div>

          <!-- Pagination -->
          <div class="pagination-wrapper">
            <v-pagination
              v-model="logQueryParams.pageNum"
              :length="Math.ceil(logTotal / logQueryParams.pageSize) || 1"
              density="comfortable"
              @update:model-value="getJobLogList"
            />
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="logOpen = false">关 闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Log Detail Dialog -->
    <v-dialog
      v-model="detailOpen"
      :width="appStore.device === 'mobile' ? '92%' : '600px'"
      class="log-detail-dialog"
    >
      <v-card title="执行记录详情">
        <v-card-text v-if="logDetail">
          <table class="detail-table" :class="{ 'detail-table--mobile': appStore.device === 'mobile' }">
            <tbody>
              <tr>
                <td class="detail-label">日志ID</td>
                <td class="detail-value">{{ logDetail.jobLogId }}</td>
                <td class="detail-label">执行状态</td>
                <td class="detail-value">
                  <StatusChip :type="logDetail.status === '0' ? 'success' : 'error'" :text="logDetail.status === '0' ? '成功' : '失败'" />
                </td>
              </tr>
              <tr>
                <td class="detail-label">任务名称</td>
                <td class="detail-value" colspan="3">{{ logDetail.jobName }}</td>
              </tr>
              <tr>
                <td class="detail-label">任务组名</td>
                <td class="detail-value" colspan="3">{{ logDetail.jobGroup }}</td>
              </tr>
              <tr>
                <td class="detail-label">调用目标</td>
                <td class="detail-value" colspan="3">{{ logDetail.invokeTarget }}</td>
              </tr>
              <tr>
                <td class="detail-label">日志信息</td>
                <td class="detail-value" colspan="3">{{ logDetail.jobMessage || '-' }}</td>
              </tr>
              <tr v-if="logDetail.exceptionInfo">
                <td class="detail-label">异常信息</td>
                <td class="detail-value" colspan="3"><pre class="exception-text">{{ logDetail.exceptionInfo }}</pre></td>
              </tr>
              <tr>
                <td class="detail-label">开始时间</td>
                <td class="detail-value" colspan="3">{{ formatTime(logDetail.startTime) }}</td>
              </tr>
              <tr>
                <td class="detail-label">结束时间</td>
                <td class="detail-value" colspan="3">{{ formatTime(logDetail.endTime) }}</td>
              </tr>
              <tr v-if="logDetail.startTime && logDetail.endTime">
                <td class="detail-label">耗时</td>
                <td class="detail-value" colspan="3">{{ formatDuration(logDetail.startTime, logDetail.endTime) }}</td>
              </tr>
            </tbody>
          </table>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="detailOpen = false">关 闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import { ref, reactive, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { getJobListApi, addJobApi, updateJobApi, deleteJobApi, changeJobStatusApi, runJobApi } from '@/api/monitor/job'
import { getJobLogListApi, getJobLogDetailApi } from '@/api/monitor/jobLog'
import { useAppStore } from '@/stores/app'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { useTaskList } from '@/composables/useTaskList'
import type { SearchParams, PageResult } from '@/types'

const appStore = useAppStore()
const showSearch = ref(window.innerWidth >= 768)

const jobHeaders = [
  { title: '任务名称', key: 'jobName', minWidth: '140' },
  { title: 'cron执行表达式', key: 'cronExpression', width: '140', align: 'center' as const },
  { title: '状态', key: 'status', align: 'center' as const, width: '90' },
  { title: '操作', key: 'actions', align: 'center' as const, width: '240', sortable: false }
]

const logHeaders = [
  { title: 'ID', key: 'jobLogId', width: '70', align: 'center' as const },
  { title: '任务名称', key: 'jobName', width: '140' },
  { title: '调用目标', key: 'invokeTarget', minWidth: '180' },
  { title: '状态', key: 'status', align: 'center' as const, width: '80' },
  { title: '日志信息', key: 'jobMessage', minWidth: '200' },
  { title: '耗时', key: 'duration', align: 'center' as const, width: '100', sortable: false },
  { title: '开始时间', key: 'startTime', width: '160', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '90', sortable: false }
]

const {
  taskList: jobList, loading, total, queryParams,
  getList, handleQuery, resetQuery, queryRef,
  open, dialogTitle, submitLoading, formRef, form,
  handleUpdate, submitForm
} = useTaskList<SearchParams & { jobName?: string; jobGroup?: string; status?: string }>({
  listApi: getJobListApi,
  addApi: addJobApi,
  updateApi: updateJobApi,
  deleteApi: deleteJobApi,
  // 该页当前无批量执行 UI，仅支持单个 jobId 执行
  executeApi: (ids: number[]) => runJobApi(ids[0]),
  idField: 'jobId',
  initForm: () => ({
    jobId: undefined,
    jobName: undefined,
    jobGroup: 'DEFAULT',
    invokeTarget: undefined,
    cronExpression: undefined,
    subPost: undefined,
    concurrent: '0',
    status: '0',
    remark: undefined
  }),
  rules: {
    jobName: [{ required: true, message: '任务名称不能为空', trigger: 'blur' }],
    jobGroup: [{ required: true, message: '任务组名不能为空', trigger: 'blur' }],
    invokeTarget: [{ required: true, message: '调用目标字符串不能为空', trigger: 'blur' }],
    cronExpression: [{ required: true, message: 'Cron表达式不能为空', trigger: 'blur' }]
  },
  defaultQuery: { jobName: undefined, jobGroup: undefined, status: undefined }
})

// ---------- 移动端 - 分页辅助 ----------
const totalPages = computed(() => Math.ceil(total.value / queryParams.pageSize) || 1)

const prevPage = () => {
  if (queryParams.pageNum > 1) {
    queryParams.pageNum--
    getList()
  }
}

const nextPage = () => {
  if (queryParams.pageNum < totalPages.value) {
    queryParams.pageNum++
    getList()
  }
}

const handleSizeChange = () => {
  queryParams.pageNum = 1
  getList()
}

const showCronDialog = ref(false)

const handleSwitchChange = async (row: any) => {
  const newStatus = row.status
  const text = newStatus === '0' ? '启用' : '停用'
  try {
    await confirm({ message: `是否确认${text}任务"${row.jobName}"？`, title: '警告', type: 'info' })
    await changeJobStatusApi(row.jobId, newStatus)
    message.success(`${text}成功`)
  } catch (e) {
    if (e !== 'cancel') {
      // Revert status on API failure
      row.status = row.status === '0' ? '1' : '0'
      console.error(e)
    } else {
      // User cancelled - revert status
      row.status = row.status === '0' ? '1' : '0'
    }
  }
}

const handleRun = async (row: any) => {
  try {
    await confirm({ message: `是否确认执行任务"${row.jobName}"？`, title: '警告', type: 'warning' })
    await runJobApi(row.jobId)
    message.success('执行成功')
  } catch (e) { if (e !== 'cancel') console.error(e) }
}

// ========== Job Log ==========
const logOpen = ref(false)
const logLoading = ref(false)
const logList = ref<any[]>([])
const logTotal = ref(0)
const logTitle = ref('')
const logSearchCollapsed = ref(true)
const logQueryParams = reactive<SearchParams>({
  pageNum: 1,
  pageSize: 10,
  jobName: undefined,
  status: undefined
})

const detailOpen = ref(false)
const logDetail = ref<any>(null)

const handleViewLogs = (row: any) => {
  if (!row?.jobId) {
    message.warning('请选择数据项')
    return
  }
  logTitle.value = row.jobName
  logQueryParams.pageNum = 1
  logQueryParams.jobName = row.jobName
  logOpen.value = true
  getJobLogList()
}

const getJobLogList = async () => {
  logLoading.value = true
  try {
    const res = await getJobLogListApi(logQueryParams) as PageResult
    logList.value = res.records
    logTotal.value = res.total
  } finally {
    logLoading.value = false
  }
}

const resetLogQuery = () => {
  logQueryParams.pageNum = 1
  logQueryParams.jobName = undefined
  logQueryParams.status = undefined
  getJobLogList()
}

const handleViewLogDetail = async (row: any) => {
  try {
    const res = await getJobLogDetailApi(row.jobLogId) as any
    logDetail.value = res
    detailOpen.value = true
  } catch (e) {
    console.error(e)
  }
}

const formatTime = (time: string | null): string => {
  if (!time) return '-'
  const date = new Date(time)
  if (isNaN(date.getTime())) return time
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

const formatDuration = (start: string | null, end: string | null): string => {
  if (!start || !end) return '-'
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  const diff = Math.abs(e - s)
  const ms = diff % 1000
  const sec = Math.floor(diff / 1000) % 60
  const min = Math.floor(diff / 60000)
  if (min > 0) return `${min}分${sec}秒`
  if (sec > 0) return `${sec}.${ms.toString().padStart(3, '0').slice(0, 1)}秒`
  return `${diff}毫秒`
}

getList()
</script>

<style scoped lang="scss">
.cron-field {
  display: flex;
  align-items: flex-start;
  gap: 8px;

  .cron-input {
    flex: 1;
  }
}

.detail-table {
  width: 100%;
  border-collapse: collapse;

  td {
    border: 1px solid var(--osr-border-light);
    padding: 8px 12px;
    font-size: 13px;
    vertical-align: top;
  }

  .detail-label {
    width: 110px;
    background: var(--osr-bg-page);
    color: var(--osr-text-secondary);
    font-weight: 600;
  }

  .detail-value {
    color: var(--osr-text-primary);
  }

  &--mobile {
    tr {
      display: flex;
      flex-direction: column;
    }

    .detail-label,
    .detail-value {
      width: 100%;
      display: block;
    }
  }
}

/* ============================================
   Mobile Responsive
   ============================================ */

/* ============================================
   Log Search Form
   ============================================ */
.log-search-form {
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--osr-border-light);

  /* 弹窗内嵌搜索行：inline-fields 布局宽度自定，这里给状态下拉一个合适宽度 */
  .v-select {
    max-width: 160px;
  }
}

/* ============================================
   Log Dialog (mobile fullscreen)
   ============================================ */
.log-dialog {
  :deep(.v-card-text) {
    max-height: calc(100vh - 120px);
    overflow-y: auto;
  }
}

/* ============================================
   Exception Text
   ============================================ */
.exception-text {
  max-height: 300px;
  overflow-y: auto;
  margin: 0;
  padding: 12px;
  background: rgba(var(--v-theme-error), 0.08);
  border-radius: var(--osr-radius-sm);
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: rgb(var(--v-theme-error));
}
</style>
