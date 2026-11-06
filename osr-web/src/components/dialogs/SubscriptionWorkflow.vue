<template>
  <SectionDivider>处理链路</SectionDivider>
  <v-progress-linear v-if="loading" indeterminate />
  <v-alert v-else-if="failed" type="warning" variant="tonal">处理链路加载失败 <v-btn variant="text" @click="load">重试</v-btn></v-alert>
  <div v-for="ep in workflow" :key="ep.episode" class="workflow-episode">
    <strong>{{ diagnosis?.episodes.find(e => e.episode === ep.episode)?.label || `第 ${ep.episode} 集` }}</strong>
    <div v-for="step in ep.steps" :key="step.stage" class="workflow-step">
      <div><strong>{{ step.stage }}</strong> · {{ stateText[step.state] || step.state }}</div>
      <p class="text-body-2">{{ step.message }}</p>
      <v-btn v-if="workflowLocation(step, diagnosisTarget?.id)" variant="text" size="small" color="primary" @click="go(step)">{{ step.recordId ? `查看记录 #${step.recordId}` : `检查${step.stage}` }}</v-btn>
    </div>
  </div>
  <div class="d-flex flex-wrap workflow-actions mt-3">
    <v-btn variant="outlined" size="small" @click="openRules">检查过滤规则</v-btn>
    <v-btn variant="outlined" size="small" :loading="reconciling" @click="reconcile">媒体库对账</v-btn>
  </div>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'
import { workflowLocation, type WorkflowStep } from '@/composables/useWorkflowNavigation'
import { getRoutePathForComponent } from '@/router'
import { refreshSubscriptionApi } from '@/api/openlist/ptSubscription'
import SectionDivider from '@/components/SectionDivider.vue'
const { diagnosisOpen, diagnosisTarget, diagnosis, showDiagnosis } = usePtSubscriptionContext()
const router = useRouter()
const workflow = ref<{ episode: number; steps: WorkflowStep[] }[]>([])
const loading = ref(false), failed = ref(false), reconciling = ref(false)
const stateText: Record<string, string> = { UNKNOWN: '未确认', SUCCEEDED: '成功', FAILED: '失败', RUNNING: '处理中', COMPLETED: '下载完成', DOWNLOADING: '下载中', PUSHED: '已推送' }
let sequence = 0
async function load() {
  const id = diagnosisTarget.value?.id
  if (!id || !diagnosisOpen.value) return
  const seq = ++sequence
  loading.value = true; failed.value = false; workflow.value = []
  try {
    const result = await request.get<any, typeof workflow.value>(`/openliststrm/pt-subscriptions/${id}/workflow`, { silent: true })
    if (seq === sequence && diagnosisTarget.value?.id === id) workflow.value = result
  } catch { if (seq === sequence) failed.value = true }
  finally { if (seq === sequence) loading.value = false }
}
watch(() => diagnosisOpen.value ? diagnosisTarget.value?.id : null, () => { sequence++; load() }, { immediate: true })
function go(step: WorkflowStep) {
  const location = workflowLocation(step, diagnosisTarget.value?.id)
  if (location) { diagnosisOpen.value = false; router.push(location) }
}
function openRules() {
  const path = getRoutePathForComponent('openlist/ptFilterConfig/index')
  if (path) { diagnosisOpen.value = false; router.push(path) }
}
async function reconcile() {
  const target = diagnosisTarget.value
  if (!target) return
  reconciling.value = true
  try { await refreshSubscriptionApi(target.id); await showDiagnosis(target); await load() }
  finally { reconciling.value = false }
}
</script>
<style scoped>
.workflow-actions { gap: 8px; }
.workflow-episode { margin-top: 16px; }
.workflow-step { border-left: 2px solid var(--osr-border-light); padding: 8px 12px; overflow-wrap: anywhere; }
</style>
