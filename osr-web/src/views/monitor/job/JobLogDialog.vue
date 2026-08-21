<template>
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
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { getJobLogListApi, getJobLogDetailApi } from '@/api/monitor/jobLog'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import { useAppStore } from '@/stores/app'
import StatusChip from '@/components/StatusChip.vue'
import type { SearchParams, PageResult } from '@/types'

/**
 * 定时任务的执行日志弹窗（连同「日志详情」二级弹窗）。
 *
 * 从 monitor/job 页面里整块搬出来：它自带查询条件、分页、两级弹窗与两个时间格式化
 * 函数，与外面那张任务表除了「看哪个任务」之外没有任何共享状态——留在页面里只是
 * 让那个文件长了近 200 行。
 */
const props = defineProps<{ jobName: string }>()
const logOpen = defineModel<boolean>({ default: false })

const appStore = useAppStore()

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

/** 打开时按当前任务名重新查一次：弹窗是常驻的，不重置会看到上一个任务的日志 */
watch(logOpen, (open) => {
  if (!open) return
  logTitle.value = props.jobName
  logQueryParams.pageNum = 1
  logQueryParams.jobName = props.jobName
  logQueryParams.status = undefined
  getJobLogList()
})
</script>

<style scoped lang="scss">
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
  font-family: var(--osr-font-mono);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: rgb(var(--v-theme-error));
}
</style>
