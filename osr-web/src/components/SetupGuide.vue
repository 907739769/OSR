<template>
  <v-dialog v-model="setupGuideOpen" :width="isMobile ? '92%' : undefined" max-width="900" scrollable>
    <v-card title="首次配置引导">
      <v-card-text>
        <p class="text-body-2 mb-3">选择使用场景，按清单完成配置，再用一个小文件检查流程。已有设置会自动识别。</p>
        <v-select v-model="mode" label="使用场景" :items="[{ title: '只做网盘 STRM', value: 'STRM' }, { title: '自动追剧 → 网盘 → STRM → 入库', value: 'PT' }]" hide-details class="mb-3" />
        <v-alert v-if="failed" type="warning" variant="tonal" class="mb-3">配置检查失败，请重试，不能据此判断配置是否完整。</v-alert>
        <v-progress-linear v-if="loading" indeterminate />
        <template v-if="steps.length">
          <div class="text-body-2 mb-3">必选配置 {{ steps.filter(s => s.required && s.configured).length }} / {{ steps.filter(s => s.required).length }} 已填写。已配置不代表连接测试已通过。</div>
          <div v-for="step in steps" :key="step.key" class="setup-step">
            <div><strong>{{ step.title }}</strong> · {{ step.configured ? '已配置' : '待配置' }}{{ step.required ? '' : '（可选）' }}</div>
            <p class="text-body-2">{{ step.hint }}</p>
            <v-btn v-if="pathFor(step.component)" size="small" variant="tonal" color="primary" @click="go(step.component)">{{ step.configured ? '检查 / 测试连接' : '去配置' }}</v-btn>
          </div>
        </template>
        <SectionDivider>运行一个样例</SectionDivider>
        <p class="text-body-2 mb-3">先在任务页手动执行一个小目录，再填原始视频文件名，查看最近同名文件的处理记录。请打开记录核对路径；最终入库与播放需在媒体库确认。</p>
        <v-text-field v-model="fileName" label="原始视频文件名" placeholder="例如：示例.S01E01.mkv" hide-details @keyup.enter="verify" />
        <v-btn class="my-3" variant="outlined" :loading="verifying" :disabled="!fileName.trim()" @click="verify">查询样例记录</v-btn>
        <v-alert v-if="verifyFailed" type="warning" variant="tonal">样例查询失败，请核对文件名后重试。</v-alert>
        <div v-for="item in evidence" :key="item.stage" class="setup-step">
          <div><strong>{{ item.stage }}</strong> · {{ resultLabels[item.state] || item.state }}</div>
          <p class="text-body-2">{{ item.message }}</p>
          <v-btn v-if="workflowLocation(item)" variant="text" size="small" color="primary" @click="inspect(item)">{{ item.recordId ? `查看记录 #${item.recordId}` : '查看任务记录' }}</v-btn>
        </div>
        <v-btn v-if="pathFor('openlist/ptMediaServer/index')" variant="text" size="small" class="mt-2" @click="go('openlist/ptMediaServer/index')">检查媒体库路径与扫描</v-btn>
      </v-card-text>
      <v-card-actions class="flex-wrap">
        <v-btn variant="text" :loading="loading" @click="load(false)">重新检查配置</v-btn>
        <v-spacer />
        <v-btn variant="outlined" @click="dismiss">稍后设置</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import request from '@/api/request'
import { getRoutePathForComponent as pathFor } from '@/router'
import { setupGuideOpen } from '@/composables/useSetupGuide'
import { workflowLocation, type WorkflowStep } from '@/composables/useWorkflowNavigation'
import SectionDivider from '@/components/SectionDivider.vue'
interface SetupStep { key: string; title: string; configured: boolean; required: boolean; component: string; hint: string }
const user = useUserStore(), app = useAppStore(), router = useRouter(), route = useRoute()
const isMobile = computed(() => app.device === 'mobile')
const storageKey = `osr-setup-${user.userInfo?.userId}`
const read = (key: string) => { try { return localStorage.getItem(key) } catch { return null } }
const write = (key: string, value: string) => { try { localStorage.setItem(key, value) } catch { /* 浏览器禁止存储时仍可配置 */ } }
const mode = ref(read(storageKey + '-mode') === 'PT' ? 'PT' : 'STRM')
const steps = ref<SetupStep[]>([]), evidence = ref<WorkflowStep[]>([]), fileName = ref('')
const loading = ref(false), failed = ref(false), verifying = ref(false), verifyFailed = ref(false)
const resultLabels: Record<string, string> = { SUCCEEDED: '成功', FAILED: '失败', UNKNOWN: '未确认', PENDING: '待处理', RUNNING: '处理中' }
let sequence = 0
async function load(auto: boolean) {
  const seq = ++sequence
  loading.value = true; failed.value = false
  try {
    const result = await request.get<any, SetupStep[]>('/openliststrm/setup-guide', { params: { mode: mode.value }, silent: true })
    if (seq !== sequence) return
    steps.value = Array.isArray(result) ? result : []
    if (auto && !read(storageKey + '-dismissed') && steps.value.some(s => s.required && !s.configured)) setupGuideOpen.value = true
  } catch { if (seq === sequence) failed.value = true }
  finally { if (seq === sequence) loading.value = false }
}
watch(mode, value => { write(storageKey + '-mode', value); evidence.value = []; load(false) })
watch(setupGuideOpen, value => { if (value) load(false) })
watch(() => route.path, value => { if (value === '/dashboard') load(true) })
onMounted(() => load(true))
onBeforeUnmount(() => { sequence++ })
function dismiss() { write(storageKey + '-dismissed', '1'); setupGuideOpen.value = false }
function go(component: string) {
  const path = pathFor(component)
  if (path) { setupGuideOpen.value = false; router.push(path) }
}
async function verify() {
  if (!fileName.value.trim() || verifying.value) return
  verifying.value = true; verifyFailed.value = false; evidence.value = []
  try { evidence.value = await request.get<any, WorkflowStep[]>('/openliststrm/setup-guide/verify', { params: { fileName: fileName.value.trim() }, silent: true }) }
  catch { verifyFailed.value = true }
  finally { verifying.value = false }
}
function inspect(item: WorkflowStep) {
  const location = workflowLocation(item)
  if (location) { setupGuideOpen.value = false; router.push(location) }
}
</script>
<style scoped>
.setup-step { padding: 14px 0; border-bottom: 1px solid var(--osr-border-light); overflow-wrap: anywhere; }
.setup-step p { margin: 6px 0; }
</style>
