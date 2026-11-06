<template>
  <v-dialog
    :model-value="modelValue"
    :max-width="isMobile ? undefined : maxWidth"
    :width="isMobile ? '92%' : undefined"
    :persistent="submitting"
    @update:model-value="(v: boolean) => v ? emit('update:modelValue', true) : requestClose()"
  >
    <v-card :title="title">
      <v-card-text>
        <slot />
      </v-card-text>
      <v-card-actions>
        <!-- 左侧的次要动作（测试连接、预览……），没有就不占位 -->
        <slot name="extra" />
        <v-spacer />
        <v-btn variant="outlined" :disabled="submitting" @click="requestClose">取消</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" @click="emit('submit')">
          {{ submitText }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { computed, inject, onMounted, onBeforeUnmount } from 'vue'
import { matchedRouteKey, onBeforeRouteLeave } from 'vue-router'
import { confirm } from '@/composables/useConfirm'
import { useAppStore } from '@/stores/app'

/**
 * 两端共用的表单弹窗外壳：宽度、标题、取消/确定这三件事在 20 个成对页面里逐字重复，
 * 而它们之间**唯一**的真实差异就是宽度档位（PC `max-width` 三档 / 移动端 `width="92%"`）。
 *
 * 宽度按 `stores/app.ts` 的 device 自己判，不做成 prop：判据与
 * `createDeviceView` 选哪一端实现是同一个（`MOBILE_MEDIA_QUERY`），
 * 交给调用方传就多出一个可以传错、且传错了也不会报错的地方。
 */
const props = withDefaults(defineProps<{
  modelValue: boolean
  title?: string
  submitting?: boolean
  /** 有未保存修改时，关闭弹窗或离开页面前确认 */
  dirty?: boolean
  /** PC 下的宽度档位：480 确认类 / 600 表单类 / 900 数据表类 */
  maxWidth?: number | string
  submitText?: string
}>(), {
  title: '',
  submitting: false,
  dirty: false,
  maxWidth: 600,
  submitText: '确定'
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit'): void
}>()

const appStore = useAppStore()
const isMobile = computed(() => appStore.device === 'mobile')

let pendingDiscard: Promise<boolean> | null = null
function canDiscard(): Promise<boolean> {
  if (!props.modelValue) return Promise.resolve(true)
  if (props.submitting) return Promise.resolve(false)
  if (!props.dirty) return Promise.resolve(true)
  if (!pendingDiscard) {
    pendingDiscard = confirm({
      title: '放弃修改？',
      message: '表单的修改还没有保存，离开后将丢失。',
      confirmText: '放弃修改',
      cancelText: '继续编辑',
      type: 'warning'
    }).then(() => true, () => false).finally(() => { pendingDiscard = null })
  }
  return pendingDiscard
}

async function requestClose() {
  if (await canDiscard()) emit('update:modelValue', false)
}

// 弹窗也可以独立挂载；仅在实际路由页面中注册离开保护。
if (inject(matchedRouteKey, undefined)) {
  onBeforeRouteLeave(async () => {
    if (!(await canDiscard())) return false
    // 页面可能被 keep-alive 缓存，离开时也要关闭弹窗，避免返回后再展示已放弃的编辑。
    if (props.modelValue) emit('update:modelValue', false)
    return true
  })
}
const beforeUnload = (event: BeforeUnloadEvent) => {
  if (!props.modelValue || !props.dirty) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>
