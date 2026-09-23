<template>
  <div class="page-container">
    <!-- Header -->
    <PageHeader icon="settings" title="参数设置" desc="系统全局参数配置 — 开关直接切换即时生效，其余点「编辑」修改后保存">
      <template #actions>
        <div class="config-header-actions">
          <v-text-field
            v-model="searchQuery"
            class="config-search"
            prepend-inner-icon="search"
            placeholder="搜索名称 / 键名 / 说明"
            density="compact"
            variant="outlined"
            hide-details
            clearable
          />
          <v-btn color="primary" variant="outlined" prepend-icon="refresh-cw" :loading="refreshing" @click="handleRefreshCache">
            刷新缓存
          </v-btn>
        </div>
      </template>
    </PageHeader>

    <!-- Loading：只在首次加载时整块替换，保存后不再走这里。
         骨架照着「标签条 + 分节表单」的形状画，数据到了原地换成真表单，不跳版 -->
    <v-card v-if="loading" class="table-card">
      <div class="config-skeleton-tabs osr-skeleton" aria-hidden="true">
        <span v-for="w in [72, 88, 64, 96, 80]" :key="w" class="osr-bone" :style="{ width: `${w}px` }" />
      </div>
      <SkeletonForm :sections="3" :fields="4" />
    </v-card>

    <template v-else>
      <!-- 搜索态：跨标签平铺所有命中项，按分组分小节 -->
      <v-card v-if="searching" class="table-card config-panel">
        <p class="config-search__summary">
          共 {{ searchHitCount }} 项匹配「{{ searchQuery.trim() }}」
        </p>
        <template v-if="searchSections.length > 0">
          <section v-for="section in searchSections" :key="section.key" class="config-section">
            <h3 class="config-section__title">
              <v-icon :icon="section.icon" size="16" />
              {{ section.title }}
            </h3>
            <div class="section-cards">
              <ConfigItem
                v-for="item in section.items"
                :key="item.configId"
                v-bind="itemBindings(item)"
                v-model:form="editForm"
                v-model:number="editNumber"
                v-on="itemHandlers"
              />
            </div>
          </section>
        </template>
        <v-empty-state v-else icon="search" title="没有匹配的参数" text="换个关键词试试，键名、中文名和说明文字都能搜" />
      </v-card>

      <!-- 标签态 -->
      <v-card v-else-if="configTabs.length > 0" class="table-card">
        <v-tabs :model-value="activeTab" color="primary" class="config-tabs" show-arrows @update:model-value="onTabChange">
          <v-tab v-for="tab in configTabs" :key="tab.key" :value="tab.key">
            <v-icon :icon="tab.icon" size="18" class="config-tab__icon" />
            {{ tab.title }}
            <span class="config-tab__count">{{ tab.count }}</span>
          </v-tab>
        </v-tabs>

        <v-window :model-value="activeTab" class="config-window">
          <v-window-item v-for="tab in configTabs" :key="tab.key" :value="tab.key">
            <section v-for="section in tab.sections" :key="section.key" class="config-section">
              <!-- 一个标签只有一个分组时，小节标题与标签重复，不显示 -->
              <h3 v-if="tab.sections.length > 1" class="config-section__title">
                <v-icon :icon="section.icon" size="16" />
                {{ section.title }}
              </h3>
              <div class="section-cards">
                <ConfigItem
                  v-for="item in section.items"
                  :key="item.configId"
                  v-bind="itemBindings(item)"
                  v-model:form="editForm"
                  v-model:number="editNumber"
                  v-on="itemHandlers"
                />
              </div>
            </section>
          </v-window-item>
        </v-window>
      </v-card>

      <!-- Empty State -->
      <v-empty-state v-else icon="settings" title="暂无参数配置" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { getConfigListApi, updateConfigApi, refreshCacheApi } from '@/api/system/config'
import type { SysConfig } from '@/types/system'
import { SECTION_RULES, CONFIG_TABS, HIDDEN_KEYS, metaOf, sectionKeyOf, matchesQuery } from './configMeta'
import PageHeader from '@/components/PageHeader.vue'
import ConfigItem from './ConfigItem.vue'
import SkeletonForm from '@/components/skeleton/SkeletonForm.vue'

interface ConfigSection {
  key: string
  title: string
  icon: string
  tab: string
  items: SysConfig[]
}

interface ConfigTab {
  key: string
  title: string
  icon: string
  count: number
  sections: ConfigSection[]
}

const loading = ref(true)
const refreshing = ref(false)
const saving = ref(false)
const switchSavingId = ref<number | null>(null)
const configList = ref<SysConfig[]>([])

// 当前激活的标签（默认第一个）
const activeTab = ref(CONFIG_TABS[0].key)
const searchQuery = ref('')

// Editing state
const editingId = ref<number | null>(null)
const editForm = ref<Partial<SysConfig>>({})
const editNumber = ref<number>(0)
const editError = ref('')

/** 按分组归桶，顺序遵循 SECTION_RULES 的声明顺序，空分组不出现 */
const buildSections = (list: SysConfig[]): ConfigSection[] => {
  const buckets: Record<string, SysConfig[]> = {}
  for (const config of list) {
    const key = config.configKey || ''
    if (HIDDEN_KEYS.has(key)) continue
    ;(buckets[sectionKeyOf(key)] ||= []).push(config)
  }
  return SECTION_RULES
    .filter((rule) => buckets[rule.key]?.length)
    .map((rule) => ({ key: rule.key, title: rule.title, icon: rule.icon, tab: rule.tab, items: buckets[rule.key] }))
}

const configTabs = computed<ConfigTab[]>(() => {
  const sections = buildSections(configList.value)
  return CONFIG_TABS
    .map((tab) => {
      const own = sections.filter(s => s.tab === tab.key)
      return { ...tab, sections: own, count: own.reduce((n, s) => n + s.items.length, 0) }
    })
    .filter(tab => tab.count > 0)
})

const searching = computed(() => searchQuery.value?.trim().length > 0)
const searchSections = computed<ConfigSection[]>(() =>
  searching.value ? buildSections(configList.value.filter(c => matchesQuery(c, searchQuery.value))) : []
)
const searchHitCount = computed(() => searchSections.value.reduce((n, s) => n + s.items.length, 0))

// 标签变化时确保 activeTab 有效：避免分组调整后默认标签失配导致首屏空白
watch(configTabs, (tabs) => {
  if (tabs.length > 0 && !tabs.some(t => t.key === activeTab.value)) {
    activeTab.value = tabs[0].key
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

/** 更新接口要整条回传，只替换 configValue */
const buildPayload = (config: SysConfig, configValue: string) => ({
  configId: config.configId,
  configName: config.configName,
  configKey: config.configKey,
  configValue,
  configType: config.configType,
  createTime: config.createTime,
  updateTime: config.updateTime,
  remark: config.remark
})

// 开关内联即时保存
const toggleSwitch = async (config: SysConfig, val: boolean) => {
  const newValue = val ? '1' : '0'
  switchSavingId.value = config.configId
  try {
    await updateConfigApi(buildPayload(config, newValue))
    config.configValue = newValue
    message.success(`${config.configName} 已${val ? '开启' : '关闭'}`)
  } catch (error: any) {
    message.error(error.msg || error.message || '保存失败')
  } finally {
    switchSavingId.value = null
  }
}

/* ---------------- 编辑 ---------------- */

const editingConfig = computed(() =>
  editingId.value == null ? null : configList.value.find(c => c.configId === editingId.value) ?? null
)

/** 编辑框里的值（数字类型取数字输入框） */
const pendingValue = (config: SysConfig): string => {
  return metaOf(config).type === 'number'
    ? String(editNumber.value ?? '')
    : String(editForm.value.configValue ?? '')
}

/** 正在编辑且改过值：此时切走会丢掉输入 */
const isDirty = computed(() => {
  const config = editingConfig.value
  return !!config && pendingValue(config) !== (config.configValue ?? '')
})

/** 有未保存修改时先问一句；返回 false 表示用户选择留下 */
const confirmDiscard = async (): Promise<boolean> => {
  if (!isDirty.value) return true
  try {
    await confirm({
      title: '放弃修改？',
      message: `「${editingConfig.value?.configName}」的修改还没有保存，离开后将丢失。`,
      confirmText: '放弃修改',
      cancelText: '继续编辑',
      type: 'warning'
    })
    return true
  } catch {
    return false
  }
}

const onTabChange = async (tab: unknown) => {
  if (tab === activeTab.value) return
  if (!(await confirmDiscard())) return
  cancelEdit()
  activeTab.value = String(tab)
}

const startEdit = async (config: SysConfig) => {
  if (editingId.value === config.configId) return
  if (!(await confirmDiscard())) return
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

/** 数字类型的范围校验；通过返回 null */
const validateNumber = (config: SysConfig): string | null => {
  const meta = metaOf(config)
  const n = editNumber.value as unknown
  if (n === '' || n == null || !Number.isFinite(Number(n))) return '请输入数字'
  if (!Number.isInteger(Number(n))) return '请输入整数'
  if (meta.min != null && Number(n) < meta.min) return `不能小于 ${meta.min}`
  if (meta.max != null && Number(n) > meta.max) return `不能大于 ${meta.max}`
  return null
}

const saveEdit = async (original: SysConfig) => {
  const meta = metaOf(original)
  if (meta.type === 'number') {
    const err = validateNumber(original)
    if (err) {
      editError.value = err
      return
    }
  }
  const value = pendingValue(original)

  if (value === '' && meta.type !== 'textarea' && meta.type !== 'text') {
    editError.value = '参数值不能为空'
    return
  }

  saving.value = true
  editError.value = ''

  try {
    await updateConfigApi(buildPayload(original, value))
    // 只改本地这一条：重拉整表会把页面换成加载态、滚动位置归零
    original.configValue = value
    message.success('保存成功')
    cancelEdit()
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
    await refreshCacheApi()
    message.success('缓存已刷新')
  } catch (error: any) {
    message.error(error.msg || error.message || '刷新缓存失败')
  } finally {
    refreshing.value = false
  }
}

/* 标签态与搜索态渲染同一个 ConfigItem，属性与事件收在一处 */
const itemBindings = (item: SysConfig) => ({
  config: item,
  editing: editingId.value === item.configId,
  saving: saving.value,
  switchSaving: switchSavingId.value === item.configId,
  error: editError.value
})

const itemHandlers = {
  edit: startEdit,
  cancel: cancelEdit,
  save: saveEdit,
  toggle: toggleSwitch,
  copy: copyText
}

getList()
</script>

<style scoped lang="scss">
/* ============================================
    Loading：骨架里的标签条，高度与 .config-tabs 一致
    ============================================ */
.config-skeleton-tabs {
  display: flex;
  align-items: center;
  gap: 32px;
  height: 48px;
  padding: 0 20px;
  border-bottom: 1px solid var(--osr-border-light);
}

/* ============================================
    Search
    ============================================ */
.config-header-actions {
  /* PageHeader 的操作区是块级容器，只放按钮的页面看不出来；这里多了一个输入框，要自己排成一行 */
  display: flex;
  align-items: center;
  gap: 8px;
}
.config-search {
  width: 240px;
  flex: 0 1 240px;
}
.config-search__summary {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--osr-text-secondary);
}
.config-panel {
  padding: 16px;
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

/* ============================================
    Section（标签内的分组小节）
    ============================================ */
.config-section + .config-section {
  margin-top: 20px;
}
.config-section__title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--osr-text-secondary);
}
.section-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding-top: 4px;
}

/* 这页只有 PC 一套、靠响应式适配手机：窄屏两列时每张卡片只剩 170px 左右，
   名称、键名、编辑按钮挤在一行里，改成单列 */
@media (max-width: 768px) {
  .section-cards {
    grid-template-columns: minmax(0, 1fr);
  }
  /* PageHeader 窄屏会折行，但操作区仍按内容宽度排，搜索框会被压到一百来像素；
     让操作区占满自己那一行（PageHeader 的根节点带本页的 scope id，:deep 够得着） */
  .page-container :deep(.page-header-actions) {
    flex: 1 1 100%;
  }
  .config-header-actions {
    width: 100%;
  }
  .config-search {
    width: auto;
    flex: 1 1 160px;
  }
}
</style>
