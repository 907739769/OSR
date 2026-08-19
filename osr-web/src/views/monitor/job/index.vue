<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-clock-outline"
      title="定时任务"
      desc="基于 Quartz 的调度任务，可手动执行并查看执行日志"
    />

    <!-- Search Panel -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
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
    </SearchPanel>

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

    <JobLogDialog v-model="logOpen" :job-name="logJobName" />
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import { ref, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { getJobListApi, addJobApi, updateJobApi, deleteJobApi, changeJobStatusApi, runJobApi } from '@/api/monitor/job'
import { useAppStore } from '@/stores/app'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { useTaskList } from '@/composables/useTaskList'
import type { SearchParams } from '@/types'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import JobLogDialog from './JobLogDialog.vue'

const appStore = useAppStore()
const { showSearch } = useSearchPanel()

const jobHeaders = [
  { title: '任务名称', key: 'jobName', minWidth: '140' },
  { title: 'cron执行表达式', key: 'cronExpression', width: '140', align: 'center' as const },
  { title: '状态', key: 'status', align: 'center' as const, width: '90' },
  { title: '操作', key: 'actions', align: 'center' as const, width: '240', sortable: false }
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
const logJobName = ref('')

/** 打开日志弹窗：这里只负责「是哪个任务」，查询/分页/详情全在 JobLogDialog 内部 */
const handleViewLogs = (row: any) => {
  if (!row?.jobId) {
    message.warning('请选择数据项')
    return
  }
  logJobName.value = row.jobName
  logOpen.value = true
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
</style>
