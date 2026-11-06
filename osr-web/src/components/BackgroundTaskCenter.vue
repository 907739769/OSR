<template>
  <v-dialog v-model="taskCenterOpen" :width="isMobile ? '92%' : undefined" max-width="900" scrollable>
    <v-card title="后台任务">
      <v-card-text>
        <p class="text-body-2 mb-3">任务在服务端执行，离开页面或刷新不会中断。中止会等待当前项目结束。</p>
        <v-alert v-if="failed" type="warning" variant="tonal" density="compact" class="mb-3">任务加载失败，当前结果可能不是最新状态。<v-btn variant="text" @click="load()">重试</v-btn></v-alert>
        <div class="job-loading"><v-progress-linear v-show="loading" indeterminate /></div>
        <v-empty-state v-if="!loading && !failed && !backgroundJobs.length" icon="list-checks" title="暂无后台任务" />
        <v-expansion-panels variant="accordion">
          <v-expansion-panel v-for="job in backgroundJobs" :key="job.id" :value="job.id">
            <v-expansion-panel-title>
              <div class="job-heading">
                <strong>{{ job.kind === 'SUBSCRIPTION_SEARCH' ? '订阅补搜' : '失败下载重试' }}</strong>
                <span>{{ labels[job.state] || job.state }} · {{ jobDone(job) }}/{{ job.items.length }}</span>
                <span class="text-caption">{{ formatDateTime(job.createTime) }}</span>
              </div>
            </v-expansion-panel-title>
            <v-expansion-panel-text>
              <v-alert v-if="job.state === 'INTERRUPTED'" type="warning" variant="tonal" density="compact" class="mb-2">服务重启或执行中断，已完成的结果保留。重试前可先对账，避免重复下载。</v-alert>
              <v-progress-linear :model-value="job.items.length ? jobDone(job) / job.items.length * 100 : 0" :indeterminate="jobActive(job) && !jobDone(job)" class="mb-3" />
              <div v-for="item in job.items" :key="item.targetId" class="job-item">
                <div><strong>{{ item.label }}</strong> · {{ labels[item.state] || item.state }}</div>
                <div class="text-body-2">{{ item.message }}</div>
              </div>
              <v-btn v-if="jobActive(job)" variant="outlined" :disabled="job.cancelRequested" :loading="busy === job.id" @click="cancel(job)">{{ job.cancelRequested ? '等待当前项目结束' : '中止任务' }}</v-btn>
              <v-btn v-else-if="job.items.some(i => !['SUCCEEDED', 'SKIPPED'].includes(i.state))" variant="tonal" color="primary" :loading="busy === job.id" @click="retry(job)">只重试未完成或失败项</v-btn>
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>
      </v-card-text>
      <v-card-actions><v-btn variant="text" :loading="loading" @click="load()">刷新</v-btn><v-spacer /><v-btn variant="outlined" @click="taskCenterOpen = false">关闭</v-btn></v-card-actions>
    </v-card>
  </v-dialog>
</template>
<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useAppStore } from '@/stores/app'
import { listBackgroundJobsApi, cancelBackgroundJobApi, retryBackgroundJobApi, type BackgroundJob } from '@/api/openlist/backgroundJobs'
import { taskCenterOpen, backgroundJobs, backgroundJobEpoch, jobActive, jobDone } from '@/composables/useBackgroundJobs'
import { formatDateTime } from '@/composables/dateTime'
const isMobile = computed(() => useAppStore().device === 'mobile')
const loading = ref(false), failed = ref(false), busy = ref<string | null>(null)
const labels: Record<string, string> = { QUEUED: '等待执行', RUNNING: '执行中', SUCCEEDED: '成功', SKIPPED: '无需处理', FAILED: '失败', PARTIAL: '部分失败', CANCELLED: '已中止', INTERRUPTED: '已中断' }
let active = true
let refreshing = false
async function load(silent = false) {
  if (refreshing) return
  const epoch = backgroundJobEpoch.value
  refreshing = true
  loading.value = !silent
  try {
    const jobs = await listBackgroundJobsApi()
    if (active && epoch === backgroundJobEpoch.value) { backgroundJobs.value = jobs; failed.value = false }
  } catch { if (active && epoch === backgroundJobEpoch.value) failed.value = true }
  finally { refreshing = false; loading.value = false }
}
async function cancel(job: BackgroundJob) {
  busy.value = job.id
  try { await cancelBackgroundJobApi(job.id); await load() } finally { busy.value = null }
}
async function retry(job: BackgroundJob) {
  busy.value = job.id
  try { await retryBackgroundJobApi(job.id); await load() } finally { busy.value = null }
}
watch(taskCenterOpen, value => { if (value) load() })
const visible = () => { if (!document.hidden && taskCenterOpen.value) load(true) }
let timer: ReturnType<typeof setInterval>
onMounted(() => { load(); timer = setInterval(visible, 3000); document.addEventListener('visibilitychange', visible) })
onBeforeUnmount(() => { active = false; clearInterval(timer); document.removeEventListener('visibilitychange', visible) })
</script>
<style scoped>
.job-loading { height: 4px; }
.job-heading { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.job-item { padding: 10px 0; overflow-wrap: anywhere; border-bottom: 1px solid var(--osr-border-light); }
</style>
