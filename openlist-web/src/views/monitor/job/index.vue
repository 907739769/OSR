<template>
  <div class="page-container">
    <!-- Search Panel -->
    <v-card class="search-card" v-if="showSearch">
      <v-card-text>
        <v-form ref="queryRef" @submit.prevent="handleQuery">
          <div class="search-form-row">
            <v-text-field
              v-model="queryParams.jobName"
              label="任务名称"
              placeholder="请输入任务名称"
              clearable
              density="compact"
              variant="outlined"
              hide-details
              style="max-width: 220px"
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
              style="max-width: 160px"
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
    <v-dialog v-model="open" width="650px" class="modern-dialog">
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
    <v-dialog v-model="showCronDialog" width="500px" class="modern-dialog">
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
      :width="appStore.device === 'mobile' ? '100%' : '900px'"
      :fullscreen="appStore.device === 'mobile'"
      class="modern-dialog log-dialog"
    >
      <v-card :title="`${logTitle} - 执行记录`">
        <v-card-text>
          <!-- Mobile: Collapsible Search Panel -->
          <div v-if="appStore.device === 'mobile'" class="mobile-search-panel" :class="{ collapsed: logSearchCollapsed }">
            <div class="mobile-search-panel-header" @click="logSearchCollapsed = !logSearchCollapsed">
              <span class="mobile-search-panel-title">
                <v-icon icon="mdi-magnify" size="16" />
                筛选查询
              </span>
              <v-icon icon="mdi-chevron-down" class="collapse-icon" :class="{ expanded: !logSearchCollapsed }" />
            </div>
            <div class="mobile-search-panel-body">
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
              <div class="search-actions">
                <v-btn color="primary" prepend-icon="mdi-magnify" @click="logQueryParams.pageNum = 1; getJobLogList()">
                  搜索
                </v-btn>
                <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetLogQuery">重置</v-btn>
              </div>
            </div>
          </div>

          <!-- Desktop: Inline Search -->
          <div v-else class="log-search-form">
            <v-select
              v-model="logQueryParams.status"
              :items="[{ title: '成功', value: '0' }, { title: '失败', value: '1' }]"
              label="执行状态"
              placeholder="全部状态"
              clearable
              density="compact"
              variant="outlined"
              hide-details
              style="max-width: 160px"
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
              <v-chip :color="item.status === '0' ? 'success' : 'error'" size="small" variant="tonal">
                {{ item.status === '0' ? '成功' : '失败' }}
              </v-chip>
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
          <div v-if="appStore.device === 'mobile'" class="log-card-list">
            <v-progress-linear v-if="logLoading" indeterminate color="primary" />
            <v-card
              v-for="item in logList"
              :key="item.jobLogId"
              variant="outlined"
              class="log-card"
            >
              <div class="log-card-header">
                <div class="log-card-title-row">
                  <span class="log-card-title">
                    <v-icon icon="mdi-clock-outline" size="14" />
                    {{ item.jobName }}
                  </span>
                  <v-chip :color="item.status === '0' ? 'success' : 'error'" size="small" variant="tonal">
                    {{ item.status === '0' ? '成功' : '失败' }}
                  </v-chip>
                </div>
              </div>
              <div class="log-card-body">
                <div class="log-card-row">
                  <span class="log-card-label">调用目标</span>
                  <span class="log-card-value log-card-value-clip">{{ item.invokeTarget }}</span>
                </div>
                <div class="log-card-row">
                  <span class="log-card-label">日志信息</span>
                  <span class="log-card-value log-card-value-clip" @click.stop="handleViewLogDetail(item)">{{ item.jobMessage || '-' }}</span>
                </div>
                <div class="log-card-row">
                  <span class="log-card-label">开始时间</span>
                  <span class="log-card-value log-card-value-light">{{ formatTime(item.startTime) }}</span>
                </div>
                <div class="log-card-row" v-if="item.startTime && item.endTime">
                  <span class="log-card-label">耗时</span>
                  <span class="log-card-value log-card-value-light">{{ formatDuration(item.startTime, item.endTime) }}</span>
                </div>
              </div>
              <div class="log-card-footer">
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
      :width="appStore.device === 'mobile' ? '100%' : '700px'"
      class="modern-dialog log-detail-dialog"
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
                  <v-chip :color="logDetail.status === '0' ? 'success' : 'error'" size="small" variant="tonal">
                    {{ logDetail.status === '0' ? '成功' : '失败' }}
                  </v-chip>
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
import { ref, reactive } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { getJobListApi, addJobApi, updateJobApi, deleteJobApi, changeJobStatusApi, runJobApi } from '@/api/monitor/job'
import { getJobLogListApi, getJobLogDetailApi } from '@/api/monitor/jobLog'
import { useAppStore } from '@/stores/app'
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
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ============================================
   Search Card
   ============================================ */
.search-card {
  padding: 4px 8px;
}

.search-form-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

/* ============================================
   Table Card
   ============================================ */
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

/* ============================================
   Pagination
   ============================================ */
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 12px;
}

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
@media (max-width: 768px) {
  .page-container {
    gap: 10px;
  }

  .search-form-row {
    flex-direction: column;
    align-items: stretch;

    .v-text-field,
    .v-select {
      max-width: 100% !important;
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

  /* ============================================
     Mobile Card List
     ============================================ */
  .mobile-card-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .mobile-card {
    overflow: hidden;

    .mobile-card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 12px 8px;
      border-bottom: 1px solid var(--osr-border-light);
      background: var(--osr-bg-page);

      .mobile-card-title {
        font-size: 14px;
        font-weight: 600;
        color: var(--osr-text-primary);
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        margin-right: 8px;
      }
    }

    .mobile-card-body {
      padding: 0;

      .mobile-card-row {
        display: flex;
        align-items: flex-start;
        padding: 8px 12px;
        font-size: 13px;
        border-bottom: 1px solid var(--osr-border-light);

        &:last-child {
          border-bottom: none;
        }

        .mobile-card-label {
          width: 64px;
          color: var(--osr-text-secondary);
          flex-shrink: 0;
          font-size: 12px;
          line-height: 1.5;
          padding-top: 1px;
        }

        .mobile-card-value {
          flex: 1;
          min-width: 0;
          color: var(--osr-text-primary);
          font-size: 13px;
          line-height: 1.5;
          word-break: break-all;

          &.mobile-card-value-clip {
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }

          &.mobile-card-value-light {
            color: var(--osr-text-secondary);
            font-size: 12px;
          }
        }
      }
    }

    .mobile-card-actions {
      display: flex;
      justify-content: flex-end;
      gap: 2px;
      padding: 8px 12px 10px;
      border-top: 1px solid var(--osr-border-light);
    }
  }
}

/* ============================================
   Log Search Form
   ============================================ */
.log-search-form {
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--osr-border-light);
  display: flex;
  align-items: center;
  gap: 12px;
}

/* ============================================
   Mobile Search Panel (for log dialog)
   ============================================ */
.mobile-search-panel {
  border: 1px solid var(--osr-border-light);
  border-radius: 8px;
  margin-bottom: 12px;
  overflow: hidden;

  &.collapsed {
    .mobile-search-panel-body {
      display: none;
    }
  }
}

.mobile-search-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--osr-bg-page);
  cursor: pointer;
  user-select: none;
  border-bottom: 1px solid transparent;

  .mobile-search-panel-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
    font-weight: 600;
    color: var(--osr-text-primary);
  }

  .collapse-icon {
    transition: transform 0.2s;

    &.expanded {
      transform: rotate(180deg);
    }
  }
}

.mobile-search-panel-body {
  padding: 12px 14px;

  .search-actions {
    display: flex;
    gap: 8px;
    margin-top: 8px;
  }
}

/* ============================================
   Log Card List (mobile)
   ============================================ */
.log-card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.log-card {
  overflow: hidden;

  .log-card-header {
    padding: 12px 14px 10px;
    border-bottom: 1px solid var(--osr-border-light);
    background: var(--osr-bg-page);
  }

  .log-card-title-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .log-card-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
    font-weight: 600;
    color: var(--osr-text-primary);
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    .v-icon {
      color: var(--osr-primary);
      flex-shrink: 0;
    }
  }

  .log-card-body {
    padding: 0;
  }

  .log-card-row {
    display: flex;
    align-items: flex-start;
    padding: 9px 14px;
    font-size: 13px;
    border-bottom: 1px solid var(--osr-border-light);

    &:last-child {
      border-bottom: none;
    }

    .log-card-label {
      width: 72px;
      color: var(--osr-text-secondary);
      flex-shrink: 0;
      font-size: 12px;
      line-height: 1.5;
      padding-top: 1px;
    }

    .log-card-value {
      flex: 1;
      min-width: 0;
      color: var(--osr-text-primary);
      font-size: 13px;
      line-height: 1.5;
      word-break: break-all;
      cursor: default;

      &.log-card-value-clip {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      &.log-card-value-light {
        color: var(--osr-text-secondary);
        font-size: 12px;
      }
    }
  }

  .log-card-footer {
    display: flex;
    justify-content: flex-end;
    padding: 8px 14px 10px;
    border-top: 1px solid var(--osr-border-light);
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
  background: var(--osr-bg-content, #1e1e1e);
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--osr-text-danger, #f56c6c);
}
</style>
