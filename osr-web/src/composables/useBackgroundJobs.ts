import { ref } from 'vue'
import { submitBackgroundJobApi, type BackgroundJob, type JobKind } from '@/api/openlist/backgroundJobs'

// 全局入口只有可见性与当前会话的任务缓存；持久化进度全部由服务端提供。
export const taskCenterOpen = ref(false)
export const backgroundJobs = ref<BackgroundJob[]>([])
export const backgroundJobEpoch = ref(0)
export const openTaskCenter = () => { taskCenterOpen.value = true }
export function clearBackgroundJobs() {
  taskCenterOpen.value = false
  backgroundJobs.value = []
  backgroundJobEpoch.value++
}
export function jobActive(job: BackgroundJob) { return ['QUEUED', 'RUNNING'].includes(job.state) }
export function jobDone(job: BackgroundJob) { return job.items.filter(i => ['SUCCEEDED', 'SKIPPED', 'FAILED'].includes(i.state)).length }
export async function startBackgroundJob(kind: JobKind, ids: number[]) {
  const epoch = backgroundJobEpoch.value
  const job = await submitBackgroundJobApi(kind, [...ids])
  if (epoch !== backgroundJobEpoch.value) return job
  backgroundJobs.value = [job, ...backgroundJobs.value.filter(j => j.id !== job.id)]
  taskCenterOpen.value = true
  return job
}
