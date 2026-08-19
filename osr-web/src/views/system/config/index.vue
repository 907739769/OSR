<template>
  <div class="page-container">
    <!-- Header -->
    <PageHeader icon="mdi-cog" title="参数设置" desc="系统全局参数配置 — 开关直接切换即时生效，其余点击 ✏️ 编辑后保存">
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

    <!-- Config Tabs -->
    <template v-else>
      <v-card v-if="configSections.length > 0" class="table-card">
        <v-tabs v-model="activeTab" color="primary" class="config-tabs" @update:model-value="cancelEdit">
          <v-tab v-for="section in configSections" :key="section.key" :value="section.key">
            <v-icon :icon="section.icon" size="18" class="config-tab__icon" />
            {{ section.title }}
            <span class="config-tab__count">{{ section.items.length }}</span>
          </v-tab>
        </v-tabs>

        <v-window v-model="activeTab" class="config-window">
          <v-window-item v-for="section in configSections" :key="section.key" :value="section.key">
            <div class="section-cards">
              <ConfigItem
                v-for="item in section.items"
                :key="item.configId"
                :config="item"
                :editing="editingId === item.configId"
                :saving="saving"
                :switch-saving="switchSavingId === item.configId"
                v-model:form="editForm"
                v-model:number="editNumber"
                :error="editError"
                @edit="startEdit"
                @cancel="cancelEdit"
                @save="saveEdit"
                @toggle="toggleSwitch"
                @copy="copyText"
              />
            </div>
          </v-window-item>
        </v-window>
      </v-card>

      <!-- Empty State -->
      <v-empty-state v-else icon="mdi-cog-off-outline" title="暂无参数配置" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from '@/composables/useMessage'
import { getConfigListApi, updateConfigApi } from '@/api/system/config'
import type { SysConfig } from '@/types/system'
import { SECTION_RULES, HIDDEN_KEYS, metaOf, sectionKeyOf } from './configMeta'
import PageHeader from '@/components/PageHeader.vue'
import ConfigItem from './ConfigItem.vue'

interface ConfigSection {
  key: string
  title: string
  icon: string
  items: SysConfig[]
}



const loading = ref(true)
const refreshing = ref(false)
const saving = ref(false)
const switchSavingId = ref<number | null>(null)
const configList = ref<SysConfig[]>([])

// 当前激活的分组 tab（默认第一个分组 openlist）
const activeTab = ref('openlist')

// Editing state
const editingId = ref<number | null>(null)
const editForm = ref<Partial<SysConfig>>({})
const editNumber = ref<number>(0)
const editError = ref('')





/** 这些配置由专门的页面管理，参数设置页不重复展示 */


const configSections = computed<ConfigSection[]>(() => {
  const buckets: Record<string, SysConfig[]> = {}
  for (const config of configList.value) {
    const key = config.configKey || ''
    if (HIDDEN_KEYS.has(key)) continue
    ;(buckets[sectionKeyOf(key)] ||= []).push(config)
  }
  // 按 SECTION_RULES 的声明顺序展示，空分组不出现
  return SECTION_RULES
    .filter((rule) => buckets[rule.key]?.length)
    .map((rule) => ({ key: rule.key, title: rule.title, icon: rule.icon, items: buckets[rule.key] }))
})

// 分组变化时确保 activeTab 有效：避免 order 调整后默认 tab 失配导致首屏空白
watch(configSections, (sections) => {
  if (sections.length > 0 && !sections.some(s => s.key === activeTab.value)) {
    activeTab.value = sections[0].key
  }
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
    Config Tabs + Window
    ============================================ */
.config-tabs {
  border-bottom: 1px solid var(--osr-border-light);

  .config-tab__icon {
    margin-right: 4px;
  }

  .config-tab__count {
    margin-left: 8px;
    font-size: 11px;
    line-height: 1;
    color: var(--osr-text-secondary);
    background: var(--osr-bg-page);
    padding: 3px 7px;
    border-radius: 10px;
  }
}
.config-window {
  padding-top: 16px;
}
.section-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding-top: 4px;
}
</style>
