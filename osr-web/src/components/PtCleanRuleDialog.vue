<template>
  <v-dialog :model-value="modelValue" :max-width="mobile ? undefined : 900" :width="mobile ? '92%' : undefined"
            @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <v-card :title="`自动删种规则 - ${downloaderName}`">
      <v-card-text>
        <div class="clean-hint">
          规则按顺序从上到下匹配，取<b>第一条体积区间命中</b>的规则；体积落不进任何区间的种子不会被删除。
          辅种（同一份文件被多个站的种子共用）按<b>整组</b>处理：组内每个种子都达标才整组删除，任一个不达标整组保留。
          仍在 H&R 考核中的种子永远不删。
        </div>

        <!-- 规则列表 -->
        <div class="rule-list">
          <v-progress-linear v-if="loading" indeterminate color="primary" />
          <v-table density="compact">
            <thead>
              <tr>
                <th>顺序</th>
                <th>规则名</th>
                <th>体积区间</th>
                <th>最短做种</th>
                <th>删文件</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="rule in rules" :key="rule.id">
                <td>{{ rule.sortOrder }}</td>
                <td>{{ rule.name }}</td>
                <td>{{ sizeRangeText(rule) }}</td>
                <td>{{ rule.minSeedHours }} 小时</td>
                <td>{{ rule.deleteFiles === '0' ? '否' : '是' }}</td>
                <td><StatusChip :value="rule.enabled" /></td>
                <td>
                  <v-btn variant="text" size="small" color="primary" @click="startEdit(rule)">修改</v-btn>
                  <v-btn variant="text" size="small" color="error" @click="removeRule(rule)">删除</v-btn>
                </td>
              </tr>
              <tr v-if="!loading && rules.length === 0">
                <td colspan="7" class="empty-row">还没有规则。没有规则时不会删除任何种子。</td>
              </tr>
            </tbody>
          </v-table>
        </div>

        <!-- 规则编辑 -->
        <v-card v-if="editing" class="rule-form" variant="tonal">
          <v-card-text>
            <v-text-field v-model="form.name" label="规则名" placeholder="如：大包快删" density="comfortable" variant="outlined" />
            <div class="inline-fields">
              <v-text-field v-model.number="form.minSizeGb" label="体积下界(GB，含)" type="number" min="0"
                            density="comfortable" variant="outlined" />
              <v-text-field v-model.number="form.maxSizeGb" label="体积上界(GB，不含)" type="number" min="0"
                            placeholder="留空表示不限" density="comfortable" variant="outlined" clearable />
            </div>
            <FormField tip="种子累计做种时长达到该值才允许删除。用的是下载器统计的做种秒数，暂停期间不计入，与站点考核口径一致">
              <v-text-field v-model.number="form.minSeedHours" label="最短做种时长(小时)" type="number" min="0"
                            density="comfortable" variant="outlined" />
            </FormField>
            <FormField label="删除时连同文件一起删"
                       tip="选「否」只会从下载器移除种子，文件留在盘上——腾不出空间，一般只在临时排查时用">
              <v-radio-group v-model="form.deleteFiles" inline hide-details>
                <v-radio label="是" value="1" />
                <v-radio label="否" value="0" />
              </v-radio-group>
            </FormField>
            <FormField label="状态">
              <v-radio-group v-model="form.enabled" inline hide-details>
                <v-radio label="启用" value="1" />
                <v-radio label="停用" value="0" />
              </v-radio-group>
            </FormField>
            <FormField tip="值小的先匹配。把「大包」规则的顺序排在「小包」之前，才能实现分级删除">
              <v-text-field v-model.number="form.sortOrder" label="匹配顺序" type="number" min="0"
                            density="comfortable" variant="outlined" />
            </FormField>
            <v-text-field v-model="form.remark" label="备注" density="comfortable" variant="outlined" />
            <div class="rule-form-actions">
              <v-btn variant="outlined" @click="cancelEdit">取消</v-btn>
              <v-btn color="primary" variant="flat" :loading="submitLoading" @click="submitRule">保存规则</v-btn>
            </div>
          </v-card-text>
        </v-card>
        <v-btn v-else color="primary" variant="outlined" prepend-icon="mdi-plus" class="mt-2" @click="startAdd">
          新增规则
        </v-btn>

        <!-- 预览结果 -->
        <div v-if="previewRows.length > 0" class="preview-block">
          <div class="preview-title">
            预览结果（共 {{ previewRows.length }} 组，其中 {{ deletableCount }} 组会被删除）—— 本次<b>没有</b>删除任何东西
          </div>
          <v-table density="compact">
            <thead>
              <tr>
                <th>种子</th>
                <th>组内种子数</th>
                <th>体积</th>
                <th>判定</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, idx) in previewRows" :key="idx">
                <td class="preview-name" :title="row.name">{{ row.name }}</td>
                <td>{{ row.torrentCount }}</td>
                <td>{{ formatSize(row.sizeBytes) }}</td>
                <td>
                  <StatusChip v-if="row.deletable" type="error" :text="row.deleteFiles ? '将删除（含文件）' : '将删除（保留文件）'" />
                  <StatusChip v-else type="info" :text="row.skipReason" />
                </td>
              </tr>
            </tbody>
          </v-table>
        </div>
      </v-card-text>
      <v-card-actions>
        <v-btn variant="outlined" :loading="previewLoading" prepend-icon="mdi-eye-outline" @click="handlePreview">
          预览（不删除）
        </v-btn>
        <v-btn variant="outlined" color="error" :loading="runLoading" prepend-icon="mdi-broom" @click="confirmRun">
          立即清理
        </v-btn>
        <v-spacer />
        <v-btn variant="outlined" @click="emit('update:modelValue', false)">关闭</v-btn>
      </v-card-actions>
    </v-card>

    <!-- 执行确认：删种不可逆，必须多一步 -->
    <v-dialog v-model="runConfirmOpen" max-width="480">
      <v-card title="确认立即清理？">
        <v-card-text>
          将按当前规则删除达标的种子。<b>此操作不可撤销</b>，规则里勾了「连同文件一起删」的会把磁盘文件一并删掉。
          建议先点「预览（不删除）」确认这次会删掉哪些。
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="runConfirmOpen = false">取消</v-btn>
          <v-btn color="error" variant="flat" @click="doRun">确认清理</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import StatusChip from '@/components/StatusChip.vue'
import FormField from '@/components/FormField.vue'
import { usePtCleanRule, formatSize } from '@/composables/usePtCleanRule'
import type { PtCleanRule } from '@/api/openlist/ptCleanRule'

const props = defineProps<{
  modelValue: boolean
  /** 目标下载器 */
  downloader?: { id: number; name: string } | null
  /** 移动端用百分比宽度，PC 用固定档位 */
  mobile?: boolean
}>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const {
  rules, loading, editing, form, submitLoading,
  previewRows, previewLoading, runLoading, downloaderName,
  openFor, startAdd, startEdit, cancelEdit, submitRule, removeRule, handlePreview, handleRun
} = usePtCleanRule()

const runConfirmOpen = ref(false)

// 弹窗打开时才拉规则：下载器列表页一次渲染十几张卡片，提前拉会发出一堆无用请求
watch(
  () => [props.modelValue, props.downloader?.id],
  ([open]) => {
    if (open && props.downloader) {
      openFor(props.downloader.id, props.downloader.name)
    }
  },
  { immediate: true }
)

const deletableCount = computed(() => previewRows.value.filter(r => r.deletable).length)

const sizeRangeText = (rule: PtCleanRule) => {
  const min = rule.minSizeGb ?? 0
  return rule.maxSizeGb === null || rule.maxSizeGb === undefined
    ? `≥ ${min} GB`
    : `${min} ~ ${rule.maxSizeGb} GB`
}

const confirmRun = () => {
  runConfirmOpen.value = true
}

const doRun = async () => {
  runConfirmOpen.value = false
  await handleRun()
}
</script>

<style scoped lang="scss">
.clean-hint {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--osr-primary-subtle);
  color: var(--osr-text-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.rule-list {
  margin-bottom: 12px;
  overflow-x: auto;
}

.empty-row {
  color: var(--osr-text-secondary);
  text-align: center;
}

.rule-form {
  margin-top: 12px;
}

.rule-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.preview-block {
  margin-top: 16px;
  overflow-x: auto;
}

.preview-title {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--osr-text-secondary);
}

.preview-name {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
