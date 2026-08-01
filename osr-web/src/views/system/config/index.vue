<template>
  <div class="page-container">
    <!-- Header -->
    <PageHeader icon="mdi-cog-outline" title="参数设置" desc="系统全局参数配置 — 开关直接切换即时生效，其余点击 ✏️ 编辑后保存">
      <template #actions>
        <v-btn color="primary" variant="outlined" prepend-icon="mdi-refresh" :loading="refreshing" @click="handleRefreshCache">
          刷新缓存
        </v-btn>
      </template>
    </PageHeader>

    <!-- Loading -->
    <div v-if="loading" class="page-loading">
      <v-progress-circular indeterminate color="primary" size="36" />
      <p>正在加载参数配置...</p>
    </div>

    <!-- Config Sections -->
    <template v-else>
      <div
        v-for="section in configSections"
        :key="section.key"
        class="config-section"
      >
        <div class="section-header">
          <div class="section-icon">
            <v-icon :icon="section.icon" size="18" />
          </div>
          <h3>{{ section.title }}</h3>
          <span class="section-count">{{ section.items.length }}</span>
        </div>
        <div class="section-cards">
          <div
            v-for="item in section.items"
            :key="item.configId"
            class="config-item"
            :class="{ 'config-item--editing': editingId === item.configId }"
          >
            <!-- Header row: name + key + actions -->
            <div class="config-item__header">
              <div class="config-item__title">
                <span class="config-item__name">{{ item.configName }}</span>
                <code class="config-item__key" @click="copyText(item.configKey)" title="点击复制键名">{{ item.configKey }}</code>
              </div>
              <div class="config-item__actions">
                <!-- Inline switch for boolean configs -->
                <v-switch
                  v-if="metaOf(item).type === 'switch' && editingId !== item.configId"
                  :model-value="item.configValue === '1'"
                  :loading="switchSavingId === item.configId"
                  color="primary"
                  density="compact"
                  hide-details
                  @update:model-value="(val: any) => toggleSwitch(item, val)"
                />
                <!-- 明确的编辑按钮（PC + 移动端均清晰可见） -->
                <v-btn
                  v-else-if="editingId !== item.configId"
                  color="primary"
                  variant="outlined"
                  size="small"
                  prepend-icon="mdi-pencil-outline"
                  @click="startEdit(item)"
                >
                  编辑
                </v-btn>
                <v-chip v-else size="small" color="warning" variant="tonal">编辑中</v-chip>
              </div>
            </div>

            <!-- Hint -->
            <p v-if="metaOf(item).hint" class="config-item__hint">{{ metaOf(item).hint }}</p>

            <!-- Display value (non-switch, non-editing) -->
            <div
              v-if="editingId !== item.configId && metaOf(item).type !== 'switch'"
              class="config-item__value"
            >
              <span class="value-text" :class="{ 'value-text--empty': !item.configValue }">{{ displayValue(item) }}</span>
              <v-tooltip v-if="isSensitive(item.configKey) && item.configValue" :text="item.configValue" location="top" open-delay="300">
                <template #activator="{ props: tooltipProps }">
                  <v-icon v-bind="tooltipProps" icon="mdi-magnify-plus-outline" class="value-expand" size="14" />
                </template>
              </v-tooltip>
            </div>

            <!-- Edit Mode -->
            <template v-if="editingId === item.configId">
              <div class="edit-body">
                <!-- number -->
                <v-text-field
                  v-if="metaOf(item).type === 'number'"
                  v-model.number="editNumber"
                  type="number"
                  :min="metaOf(item).min"
                  :max="metaOf(item).max"
                  step="1"
                  density="compact"
                  variant="outlined"
                  hide-details
                  class="edit-number"
                />
                <span v-if="metaOf(item).type === 'number' && metaOf(item).unit" class="edit-unit">{{ metaOf(item).unit }}</span>

                <!-- select -->
                <v-combobox
                  v-else-if="metaOf(item).type === 'select'"
                  v-model="editForm.configValue"
                  :items="metaOf(item).options"
                  item-title="label"
                  item-value="value"
                  density="compact"
                  variant="outlined"
                  hide-details
                  class="edit-select"
                  placeholder="请选择"
                />

                <!-- password -->
                <v-text-field
                  v-else-if="metaOf(item).type === 'password'"
                  v-model="editForm.configValue"
                  type="password"
                  placeholder="请输入参数值"
                  density="compact"
                  variant="outlined"
                  hide-details
                  class="edit-input"
                  :error="!!editError"
                />

                <!-- textarea -->
                <v-textarea
                  v-else-if="metaOf(item).type === 'textarea'"
                  v-model="editForm.configValue"
                  rows="3"
                  placeholder="请输入参数值"
                  density="compact"
                  variant="outlined"
                  hide-details
                  class="edit-input"
                  :error="!!editError"
                />

                <!-- text (default) -->
                <v-text-field
                  v-else
                  v-model="editForm.configValue"
                  placeholder="请输入参数值"
                  density="compact"
                  variant="outlined"
                  hide-details
                  class="edit-input"
                  :error="!!editError"
                />
              </div>
              <p v-if="editError" class="edit-error text-caption text-error">{{ editError }}</p>
              <div class="edit-actions">
                <v-btn variant="outlined" @click="cancelEdit">取消</v-btn>
                <v-btn color="primary" variant="flat" :loading="saving" @click="saveEdit(item)">保存</v-btn>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- Empty State -->
      <v-empty-state v-if="configSections.length === 0" icon="mdi-cog-off-outline" title="暂无参数配置" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { getConfigListApi, updateConfigApi } from '@/api/system/config'
import type { SysConfig } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'

interface ConfigSection {
  key: string
  title: string
  icon: string
  items: SysConfig[]
}

type ConfigInputType = 'switch' | 'number' | 'select' | 'text' | 'password' | 'textarea'

interface ConfigMeta {
  type: ConfigInputType
  hint?: string
  unit?: string
  min?: number
  max?: number
  options?: { label: string; value: string }[]
}

const loading = ref(true)
const refreshing = ref(false)
const saving = ref(false)
const switchSavingId = ref<number | null>(null)
const configList = ref<SysConfig[]>([])

// Editing state
const editingId = ref<number | null>(null)
const editForm = ref<Partial<SysConfig>>({})
const editNumber = ref<number>(0)
const editError = ref('')

/* ============================================================
   配置项元数据：按 configKey 定义控件类型、说明、可选项等。
   未在此声明的配置键回退为普通文本输入。
   ============================================================ */
const tmdbImageLangOptions = [
  { label: '中文 (zh)', value: 'zh' },
  { label: '英语 (en)', value: 'en' },
  { label: '日语 (ja)', value: 'ja' },
  { label: '韩语 (ko)', value: 'ko' }
]
const tmdbMetaLangOptions = [
  { label: '简体中文 (zh-CN)', value: 'zh-CN' },
  { label: '繁体中文 (zh-TW)', value: 'zh-TW' },
  { label: '英语 (en-US)', value: 'en-US' },
  { label: '日语 (ja-JP)', value: 'ja-JP' },
  { label: '韩语 (ko-KR)', value: 'ko-KR' }
]
const tmdbImageSizeOptions = [
  { label: '原图 (original)', value: 'original' },
  { label: 'w780', value: 'w780' },
  { label: 'w500', value: 'w500' },
  { label: 'w342', value: 'w342' },
  { label: 'w300', value: 'w300' },
  { label: 'w185', value: 'w185' }
]

const CONFIG_META: Record<string, ConfigMeta> = {
  // Openlist 基础
  'openlist.server.url': { type: 'text', hint: 'OpenList 服务访问地址，例如 http://192.168.1.10:5244' },
  'openlist.server.token': { type: 'password', hint: 'OpenList 管理 API Token' },
  'openlist.api.apikey': { type: 'password', hint: '第三方开放回调接口的鉴权 Key' },
  'openlist.api.refresh': { type: 'switch', hint: '源目录同步列举时是否强制刷新网盘（建议开启以保证增量正确）' },
  'openlist.api.traversal.refresh': { type: 'switch', hint: '目录遍历目标目录时是否强制刷新网盘（关闭走缓存更快，默认关闭）' },
  'openlist.api.traversal.concurrency': { type: 'number', min: 1, max: 64, hint: '目录遍历并发线程数，范围 1-64，默认 10' },
  'openlist.local.allowedroots': { type: 'textarea', hint: '本地目录浏览白名单，多个用英文逗号分隔，默认仅 /data' },
  // 复制 & STRM
  'openlist.copy.minfilesize': { type: 'number', min: 0, unit: 'MB', hint: '小于该大小的文件不会被复制' },
  'openlist.copy.strm': { type: 'switch', hint: '复制完成后是否自动生成 STRM 文件' },
  'openlist.copy.monitor.maxminutes': { type: 'number', min: 1, unit: '分钟', hint: '复制任务监控最长时长，超时未结束将标记为异常，默认 600' },
  'openlist.strm.outputdir': { type: 'text', hint: 'STRM 文件生成的根目录，默认 /data/strm' },
  'openlist.strm.encode': { type: 'switch', hint: 'STRM 内路径是否进行 URL 编码' },
  'openlist.strm.downloadsub': { type: 'switch', hint: '生成 STRM 时是否同时下载字幕文件' },
  // Telegram
  'openlist.tg.token': { type: 'password', hint: 'Telegram 机器人 Token' },
  'openlist.tg.userid': { type: 'text', hint: '允许控制机器人的 Telegram 用户 ID' },
  // OpenAI
  'openlist.openai.apikey': { type: 'password', hint: 'OpenAI API Key' },
  'openlist.openai.endpoint': { type: 'text', hint: 'OpenAI 接口地址，默认 https://api.openai.com' },
  'openlist.openai.model': { type: 'text', hint: 'OpenAI 模型名称，例如 gpt-5-mini' },
  // TMDb
  'openlist.tmdb.apikey': { type: 'password', hint: 'TMDb API Key' },
  'openlist.tmdb.image.language': { type: 'select', options: tmdbImageLangOptions, hint: 'TMDb 图片语言偏好' },
  'openlist.tmdb.metadata.language': { type: 'select', options: tmdbMetaLangOptions, hint: 'TMDb 元数据（标题/简介）请求语言' },
  'openlist.tmdb.image.size': { type: 'select', options: tmdbImageSizeOptions, hint: 'TMDb 图片下载尺寸，越小越省带宽' }
}

const metaOf = (config: SysConfig): ConfigMeta => {
  return CONFIG_META[config.configKey] || { type: 'text' }
}

// Section definitions with categorization logic
const configSections = computed<ConfigSection[]>(() => {
  const sections: Record<string, ConfigSection> = {}

  const addSection = (key: string, title: string, icon: any) => {
    if (!sections[key]) {
      sections[key] = { key, title, icon, items: [] }
    }
  }

  const categorize = (config: SysConfig) => {
    const key = config.configKey || ''
    const name = config.configName || ''

    // OpenAI 相关
    if (key.includes('openai') || name.includes('openai') || name.includes('OpenAI') || name.includes('gpt') || name.includes('GPT')) {
      addSection('openai', 'OpenAI 配置', 'mdi-robot-outline')
      sections['openai'].items.push(config)
      return
    }

    // TMDB 相关
    if (key.includes('tmdb') || name.includes('TMDB') || name.includes('tmdb') || name.includes('TMDb')) {
      addSection('tmdb', 'TMDb 影视配置', 'mdi-flash-outline')
      sections['tmdb'].items.push(config)
      return
    }

    // Telegram 相关
    if (key.includes('tg.') || name.includes('tg') || name.includes('TG') || name.includes('Telegram') || name.includes('telegram')) {
      addSection('tg', 'Telegram 机器人', 'mdi-telegram')
      sections['tg'].items.push(config)
      return
    }

    // Copy / STRM 相关
    if (key.includes('copy') || key.includes('strm') || name.includes('复制') || name.includes('STRM') || name.includes('strm')) {
      addSection('copy', '复制 & STRM 任务', 'mdi-swap-horizontal')
      sections['copy'].items.push(config)
      return
    }

    // Openlist 基础配置（默认归入此类）
    addSection('openlist', 'Openlist 基础配置', 'mdi-monitor')
    sections['openlist'].items.push(config)
  }

  configList.value.forEach(categorize)

  // Define display order
  const order = ['openlist', 'copy', 'tg', 'openai', 'tmdb']
  return order
    .filter(k => sections[k])
    .map(k => sections[k])
})

const getList = async () => {
  loading.value = true
  try {
    const res = await getConfigListApi({ pageNum: 1, pageSize: 500 }) as any
    configList.value = res.records || []
  } catch (error) {
    console.error(error)
    message.error('加载参数配置失败')
  } finally {
    loading.value = false
  }
}

// Sensitive keys that should be masked
const sensitiveKeys = ['token', 'apikey', 'api_key', 'secret', 'password', 'passwd']

const isSensitive = (key: string): boolean => {
  if (!key) return false
  const lower = key.toLowerCase()
  return sensitiveKeys.some(s => lower.includes(s))
}

const displayValue = (config: SysConfig): string => {
  if (!config.configValue) return '未配置'
  // 下拉枚举：显示对应中文标签
  const meta = metaOf(config)
  if (meta.type === 'select' && meta.options) {
    const hit = meta.options.find(o => o.value === config.configValue)
    if (hit) return hit.label
  }
  // 数字：附带单位
  if (meta.type === 'number' && meta.unit) {
    return `${config.configValue} ${meta.unit}`
  }
  // 敏感值脱敏
  if (isSensitive(config.configKey)) {
    const v = config.configValue
    if (v.length <= 6) return v
    return v.slice(0, 4) + '•'.repeat(Math.min(v.length - 4, 12))
  }
  return config.configValue
}

// 开关内联即时保存
const toggleSwitch = async (config: SysConfig, val: boolean) => {
  const newValue = val ? '1' : '0'
  switchSavingId.value = config.configId
  try {
    await updateConfigApi({
      configId: config.configId,
      configName: config.configName,
      configKey: config.configKey,
      configValue: newValue,
      configType: config.configType,
      createTime: config.createTime,
      updateTime: config.updateTime,
      remark: config.remark
    })
    config.configValue = newValue
    message.success(`${config.configName} 已${val ? '开启' : '关闭'}`)
  } catch (error: any) {
    message.error(error.msg || error.message || '保存失败')
  } finally {
    switchSavingId.value = null
  }
}

// Edit functions
const startEdit = (config: SysConfig) => {
  editingId.value = config.configId
  editForm.value = { ...config }
  editError.value = ''
  if (metaOf(config).type === 'number') {
    const n = Number(config.configValue)
    editNumber.value = Number.isFinite(n) ? n : 0
  }
}

const cancelEdit = () => {
  editingId.value = null
  editForm.value = {}
  editError.value = ''
}

const saveEdit = async (original: SysConfig) => {
  const meta = metaOf(original)
  let value = editForm.value.configValue ?? ''
  if (meta.type === 'number') {
    value = String(editNumber.value ?? '')
  }

  if (value === '' && meta.type !== 'textarea' && meta.type !== 'text') {
    editError.value = '参数值不能为空'
    return
  }

  saving.value = true
  editError.value = ''

  try {
    await updateConfigApi({
      configId: original.configId,
      configName: original.configName,
      configKey: original.configKey,
      configValue: value,
      configType: original.configType,
      createTime: original.createTime,
      updateTime: original.updateTime,
      remark: original.remark
    })
    message.success('保存成功')
    editingId.value = null
    await getList()
  } catch (error: any) {
    editError.value = error.msg || error.message || '保存失败'
  } finally {
    saving.value = false
  }
}

const copyText = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制键名到剪贴板')
  } catch {
    message.error('复制失败')
  }
}

const handleRefreshCache = async () => {
  refreshing.value = true
  try {
    const { refreshCacheApi } = await import('@/api/system/config')
    await refreshCacheApi()
    message.success('缓存已刷新')
  } catch (error: any) {
    message.error(error.msg || error.message || '刷新缓存失败')
  } finally {
    refreshing.value = false
  }
}

getList()
</script>

<style scoped lang="scss">
/* ============================================
    Loading
    ============================================ */
.page-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 100px 0;
  gap: 16px;
  color: var(--osr-text-secondary);

  p { margin: 0; font-size: 14px; }
}

/* ============================================
    Config Section
    ============================================ */
.config-section {
  .section-header {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
    padding-bottom: 10px;
    border-bottom: 2px solid var(--osr-border-light);

    .section-icon {
      width: 32px;
      height: 32px;
      border-radius: var(--osr-radius-base);
      background: var(--osr-primary-subtle);
      color: var(--osr-primary);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 16px;
    }

    h3 {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
      color: var(--osr-text-primary);
    }

    .section-count {
      margin-left: auto;
      font-size: 12px;
      color: var(--osr-text-secondary);
      background: var(--osr-bg-page);
      padding: 2px 10px;
      border-radius: 10px;
    }
  }

  .section-cards {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
    padding-top: 4px;
  }
}

/* ============================================
    Config Item
    ============================================ */
.config-item {
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  border: 1px solid var(--osr-border-light);
  padding: 14px 16px;
  transition: all var(--osr-transition-base);
  display: flex;
  flex-direction: column;

  &:hover:not(.config-item--editing) {
    border-color: var(--osr-primary-muted);
    box-shadow: var(--osr-shadow-md);
  }

  &--editing {
    border-color: rgb(var(--v-theme-warning));
    box-shadow: 0 0 0 2px rgba(var(--v-theme-warning), 0.2);
    grid-column: 1 / -1;
  }

  .config-item__header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;

    .config-item__title {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
      flex: 1;

      .config-item__name {
        font-size: 14px;
        font-weight: 600;
        color: var(--osr-text-primary);
        line-height: 1.3;
      }

      .config-item__key {
        font-family: 'SF Mono', 'Courier New', monospace;
        font-size: 11px;
        color: var(--osr-text-placeholder);
        background: var(--osr-bg-page);
        padding: 1px 6px;
        border-radius: 5px;
        cursor: pointer;
        align-self: flex-start;
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        transition: all var(--osr-transition-fast);

        &:hover {
          color: var(--osr-primary);
          background: var(--osr-primary-subtle);
        }
      }
    }

    .config-item__actions {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-shrink: 0;
      padding-top: 2px;
    }
  }

  .config-item__hint {
    margin: 8px 0 0;
    font-size: 12px;
    line-height: 1.5;
    color: var(--osr-text-secondary);
  }

  .config-item__value {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
    padding: 8px 10px;
    background: var(--osr-bg-page);
    border-radius: var(--osr-radius-base);

    .value-text {
      flex: 1;
      font-family: 'SF Mono', 'Courier New', monospace;
      font-size: 13px;
      color: var(--osr-text-regular);
      line-height: 1.6;
      word-break: break-all;
      min-width: 0;

      &--empty {
        color: var(--osr-text-placeholder);
        font-style: italic;
        font-family: inherit;
      }
    }

    .value-expand {
      color: var(--osr-text-placeholder);
      cursor: pointer;
      flex-shrink: 0;
      padding: 2px;
      border-radius: 4px;
      transition: all var(--osr-transition-fast);

      &:hover {
        color: var(--osr-primary);
        background: var(--osr-primary-subtle);
      }
    }
  }
}

/* Edit mode */
.edit-body {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;

  .edit-number { width: 180px; }
  .edit-select { width: 100%; }
  .edit-input { flex: 1; }
  .edit-unit {
    font-size: 13px;
    color: var(--osr-text-secondary);
    flex-shrink: 0;
  }
}

.edit-error {
  display: block;
  margin-top: 6px;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

/* ============================================
    Mobile Responsive
    ============================================ */
@media (max-width: 768px) {

  .config-section {
    padding: 0 16px;

    .section-header {
      .section-icon {
        width: 28px;
        height: 28px;
        font-size: 14px;
      }

      h3 { font-size: 15px; }
      .section-count { display: none; }
    }

    /* 移动端单列 */
    .section-cards {
      grid-template-columns: 1fr;
      gap: 10px;
    }
  }

  .config-item {
    padding: 12px 14px;

    &--editing { grid-column: auto; }

    .config-item__name { font-size: 13px; }

    .edit-body .edit-number { width: 140px; }
  }
}
</style>
