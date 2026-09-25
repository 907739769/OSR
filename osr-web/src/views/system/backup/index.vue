<template>
  <div class="page-container">
    <PageHeader
      icon="database-backup"
      title="备份与恢复"
      desc="把各项配置与订阅导出成一个文件，换机器或重装后一键恢复"
    />

    <!-- 导出 -->
    <v-card class="table-card section-card">
      <div class="section-title">
        <v-icon icon="download" size="18" />
        <span>导出备份</span>
      </div>
      <p class="section-desc">
        包含：参数设置、通知路由、STRM / 同步 / 重命名任务与分类规则、PT 下载器 / 索引器 / 媒体服务器、
        过滤与洗版规则、删种 / 转移 / 热门自动订阅规则、种子黑名单、企业微信用户绑定、PT 订阅。
        不含各类执行记录、下载记录与日志。
      </p>
      <v-checkbox
        v-model="includeSecrets"
        label="包含敏感信息（下载器密码、API Key、Token 等，以明文写入文件）"
        density="compact"
        hide-details
        color="warning"
      />
      <v-alert v-if="includeSecrets" type="warning" variant="tonal" density="compact" class="mt-2">
        拿到这个文件就等于拿到了全部账号与密钥，请妥善保管，不要发到群聊或网盘公开目录。
      </v-alert>
      <v-alert v-else type="info" variant="tonal" density="compact" class="mt-2">
        不含敏感信息时，恢复会保留目标机器上已填的密码；目标机器上没有的下载器、索引器需恢复后补填。
      </v-alert>
      <div class="section-actions">
        <v-btn color="primary" prepend-icon="download" :loading="exporting" @click="handleExport">
          下载备份文件
        </v-btn>
      </div>
    </v-card>

    <!-- 恢复 -->
    <v-card class="table-card section-card">
      <div class="section-title">
        <v-icon icon="cloud-upload" size="18" />
        <span>从备份恢复</span>
      </div>
      <p class="section-desc">
        恢复是<b>合并</b>而不是覆盖：备份里有、本机没有的新增；两边都有的（按名称、路径等对应）以备份为准更新；
        本机有、备份里没有的原样保留。重命名分类规则例外，会整表替换。
        选好文件后会先列出每一项将发生的变化，确认后才会写入。
      </p>
      <v-file-input
        :model-value="file ? [file] : []"
        label="选择备份文件（.json）"
        accept=".json,application/json"
        prepend-icon=""
        prepend-inner-icon="file"
        density="compact"
        variant="outlined"
        clearable
        :loading="previewing"
        :disabled="previewing || restoring"
        class="file-input"
        @update:model-value="handleFileChange"
      />

      <template v-if="preview">
        <div class="preview-meta">
          <span>导出于 {{ preview.exportedAt || '未知' }}</span>
          <v-chip
            size="x-small"
            variant="tonal"
            :color="preview.includeSecrets ? 'warning' : 'default'"
          >
            {{ preview.includeSecrets ? '含敏感信息' : '不含敏感信息' }}
          </v-chip>
        </div>

        <v-table density="compact" class="preview-table">
          <thead>
            <tr>
              <th class="col-check">
                <v-checkbox-btn
                  :model-value="allSelected"
                  :indeterminate="selected.length > 0 && !allSelected"
                  density="compact"
                  @update:model-value="toggleAll"
                />
              </th>
              <th>内容</th>
              <th class="num">备份条数</th>
              <th>将发生的变化</th>
              <th class="num">跳过</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in preview.sections" :key="s.key">
              <td class="col-check">
                <v-checkbox-btn v-model="selected" :value="s.key" density="compact" />
              </td>
              <td>
                {{ s.label }}
                <v-icon
                  v-if="s.warnings.length"
                  icon="triangle-alert"
                  size="14"
                  color="warning"
                  class="ml-1"
                  :title="`${s.warnings.length + s.omittedWarnings} 条提示，见下方`"
                />
              </td>
              <td class="num">{{ s.total }}</td>
              <td>
                <span v-if="!hasChange(s)" class="muted">无变化</span>
                <span v-else class="changes">{{ describeChange(s) }}</span>
              </td>
              <td class="num">{{ s.skipped || '' }}</td>
            </tr>
          </tbody>
        </v-table>

        <div v-if="warnedSections.length" class="warnings">
          <v-alert
            v-for="s in warnedSections"
            :key="s.key"
            type="warning"
            variant="tonal"
            density="compact"
            class="mb-2"
          >
            <div class="warning-title">{{ s.label }}</div>
            <ul>
              <li v-for="(w, i) in s.warnings" :key="i">{{ w }}</li>
              <li v-if="s.omittedWarnings">…另有 {{ s.omittedWarnings }} 条</li>
            </ul>
          </v-alert>
        </div>

        <div class="section-actions">
          <v-btn
            color="primary"
            prepend-icon="history"
            :loading="restoring"
            :disabled="!selected.length || subscriptionRunning"
            @click="handleRestore"
          >
            恢复所选（{{ selected.length }} 项）
          </v-btn>
          <span v-if="subscriptionRunning" class="muted">订阅正在后台恢复，完成后才能再次恢复</span>
        </div>
      </template>

      <!-- 上一次恢复的结果 -->
      <v-alert v-if="restoreSummary" type="success" variant="tonal" density="compact" class="mt-4">
        <div class="warning-title">恢复完成</div>
        <ul>
          <li v-for="line in restoreSummary" :key="line">{{ line }}</li>
        </ul>
      </v-alert>

      <!-- 订阅后台恢复进度 -->
      <div v-if="subscriptionStatus" class="sub-status">
        <div class="sub-status-head">
          <span>
            订阅恢复{{ subscriptionStatus.running ? '中' : '已完成' }}：
            已处理 {{ subscriptionStatus.processed }} / {{ subscriptionStatus.total }}，
            新建 {{ subscriptionStatus.created }}，失败 {{ subscriptionStatus.failed }}
          </span>
          <span class="muted">开始于 {{ subscriptionStatus.startTime }}</span>
        </div>
        <v-progress-linear
          :model-value="progressPercent"
          :indeterminate="subscriptionStatus.running && subscriptionStatus.total === 0"
          :color="subscriptionStatus.failed ? 'warning' : 'primary'"
          height="6"
          rounded
        />
        <p v-if="subscriptionStatus.running" class="muted mt-1">
          每条订阅都要重新查一次 TMDb 并与媒体库对账，数量多时需要几分钟；离开本页不影响后台继续。
          恢复的订阅不会立即补搜，由定时的 RSS 与自动补搜逐步补齐，避免一次打满所有索引器。
        </p>
        <v-alert
          v-if="subscriptionStatus.errors.length"
          type="warning"
          variant="tonal"
          density="compact"
          class="mt-2"
        >
          <ul>
            <li v-for="(e, i) in subscriptionStatus.errors" :key="i">{{ e }}</li>
          </ul>
        </v-alert>
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import {
  exportBackupApi,
  previewBackupApi,
  restoreBackupApi,
  getRestoreStatusApi,
  type BackupPreview,
  type BackupSectionResult,
  type SubscriptionRestoreStatus
} from '@/api/system/backup'

/** 订阅恢复进度的轮询间隔 */
const POLL_INTERVAL_MS = 2000

const includeSecrets = ref(false)
const exporting = ref(false)

const file = ref<File | null>(null)
const previewing = ref(false)
const preview = ref<BackupPreview | null>(null)
const selected = ref<string[]>([])
const restoring = ref(false)
const restoreSummary = ref<string[] | null>(null)

const subscriptionStatus = ref<SubscriptionRestoreStatus | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

const subscriptionRunning = computed(() => !!subscriptionStatus.value?.running)

const allSelected = computed(
  () => !!preview.value && preview.value.sections.length > 0 && selected.value.length === preview.value.sections.length
)

const warnedSections = computed(() => (preview.value?.sections ?? []).filter(s => s.warnings.length))

const progressPercent = computed(() => {
  const s = subscriptionStatus.value
  return s && s.total > 0 ? Math.round((s.processed / s.total) * 100) : 0
})

function hasChange(s: BackupSectionResult) {
  return s.inserted + s.updated + s.replaced > 0
}

function describeChange(s: BackupSectionResult) {
  if (s.async) {
    return `后台新建 ${s.inserted} 条` + (s.unchanged ? `，${s.unchanged} 条已存在不动` : '')
  }
  const parts: string[] = []
  if (s.replaced) parts.push(`替换现有 ${s.replaced} 条为 ${s.inserted} 条`)
  else if (s.inserted) parts.push(`新增 ${s.inserted}`)
  if (s.updated) parts.push(`更新 ${s.updated}`)
  if (s.unchanged) parts.push(`${s.unchanged} 条相同`)
  return parts.join('，')
}

function timestamp() {
  // 本地时间：toISOString 是 UTC，东八区下文件名会比实际早 8 小时
  const d = new Date()
  const p2 = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p2(d.getMonth() + 1)}${p2(d.getDate())}-${p2(d.getHours())}${p2(d.getMinutes())}${p2(d.getSeconds())}`
}

async function handleExport() {
  if (includeSecrets.value) {
    try {
      await confirm({
        title: '导出含敏感信息的备份',
        message: '文件里会以明文写入下载器密码、索引器与各服务的 API Key / Token。确定导出？',
        type: 'warning'
      })
    } catch {
      return
    }
  }
  exporting.value = true
  try {
    const text = await exportBackupApi(includeSecrets.value)
    const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `osr-backup-${timestamp()}${includeSecrets.value ? '-secrets' : ''}.json`
    a.click()
    // 立即 revoke 会让部分浏览器（Firefox）在下载真正开始前就失去数据源
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    message.success('备份文件已开始下载')
  } catch (error) {
    // 拦截器已经弹过后端的 message
    console.error(error)
  } finally {
    exporting.value = false
  }
}

async function handleFileChange(value: File | File[] | null | undefined) {
  const picked = Array.isArray(value) ? value[0] : value
  file.value = picked ?? null
  preview.value = null
  selected.value = []
  restoreSummary.value = null
  if (!file.value) return
  previewing.value = true
  try {
    preview.value = await previewBackupApi(file.value)
    // 默认只勾有变化的项：没变化的勾上也无害，但会让确认框里的清单变长、看不出重点
    selected.value = preview.value.sections.filter(hasChange).map(s => s.key)
  } catch (error) {
    // 文件格式不对等原因拦截器已提示；清掉选择，免得用户以为还能接着点恢复
    console.error(error)
    file.value = null
  } finally {
    previewing.value = false
  }
}

function toggleAll(value: boolean | null) {
  selected.value = value && preview.value ? preview.value.sections.map(s => s.key) : []
}

async function handleRestore() {
  if (!file.value || !preview.value) return
  const chosen = preview.value.sections.filter(s => selected.value.includes(s.key))
  try {
    await confirm({
      title: '确认恢复',
      message: `将恢复：${chosen.map(s => s.label).join('、')}。` +
        '已有的同名配置会被备份里的内容覆盖（重命名分类规则整表替换），本机独有的配置不受影响。继续？',
      type: 'warning'
    })
  } catch {
    return
  }
  restoring.value = true
  try {
    const result = await restoreBackupApi(file.value, selected.value)
    restoreSummary.value = result.sections.map(s =>
      `${s.label}：${hasChange(s) ? describeChange(s) : '无变化'}${s.skipped ? `，跳过 ${s.skipped}` : ''}`
    )
    message.success('恢复完成')
    preview.value = null
    selected.value = []
    file.value = null
    if (result.subscriptionsRestoring) {
      await pollStatus()
    }
  } catch (error) {
    // 恢复失败时后端已整体回滚，失败原因由拦截器弹出
    console.error(error)
  } finally {
    restoring.value = false
  }
}

async function pollStatus() {
  stopPolling()
  try {
    subscriptionStatus.value = await getRestoreStatusApi()
  } catch (error) {
    console.error(error)
  }
  if (subscriptionStatus.value?.running) {
    pollTimer = setTimeout(pollStatus, POLL_INTERVAL_MS)
  }
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

onMounted(async () => {
  // 进页面时后台可能还在恢复（上次恢复后离开过本页），接着显示进度
  await pollStatus()
  // 上一轮早已结束的就不必再显示了，除非它还有失败明细值得一看
  const s = subscriptionStatus.value
  if (s && !s.running && s.failed === 0) {
    subscriptionStatus.value = null
  }
})

onUnmounted(stopPolling)
</script>

<style scoped lang="scss">
.section-card {
  padding: 20px;
  margin-bottom: 16px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 8px;
}

.section-desc {
  margin-bottom: 12px;
  font-size: 13px;
  line-height: 1.7;
  opacity: 0.8;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}

.file-input {
  max-width: 520px;
}

.preview-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0 8px;
  font-size: 13px;
  opacity: 0.85;
}

.preview-table {
  .col-check {
    width: 44px;
  }

  .num {
    text-align: right;
    white-space: nowrap;
  }
}

.changes {
  color: rgb(var(--v-theme-primary));
}

.muted {
  font-size: 12px;
  opacity: 0.6;
}

.warnings {
  margin-top: 12px;
}

.warning-title {
  font-weight: 600;
  margin-bottom: 4px;
}

ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
}

.sub-status {
  margin-top: 16px;
}

.sub-status-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
}
</style>
