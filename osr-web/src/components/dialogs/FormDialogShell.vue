<template>
  <v-dialog
    :model-value="modelValue"
    :max-width="isMobile ? undefined : maxWidth"
    :width="isMobile ? '92%' : undefined"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <v-card :title="title">
      <v-card-text>
        <slot />
      </v-card-text>
      <v-card-actions>
        <!-- 左侧的次要动作（测试连接、预览……），没有就不占位 -->
        <slot name="extra" />
        <v-spacer />
        <v-btn variant="outlined" @click="emit('update:modelValue', false)">取消</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" @click="emit('submit')">
          {{ submitText }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/stores/app'

/**
 * 两端共用的表单弹窗外壳：宽度、标题、取消/确定这三件事在 20 个成对页面里逐字重复，
 * 而它们之间**唯一**的真实差异就是宽度档位（PC `max-width` 三档 / 移动端 `width="92%"`）。
 *
 * 宽度按 `stores/app.ts` 的 device 自己判，不做成 prop：判据与
 * `createDeviceView` 选哪一端实现是同一个（`MOBILE_MEDIA_QUERY`），
 * 交给调用方传就多出一个可以传错、且传错了也不会报错的地方。
 */
withDefaults(defineProps<{
  modelValue: boolean
  title?: string
  submitting?: boolean
  /** PC 下的宽度档位：480 确认类 / 600 表单类 / 900 数据表类 */
  maxWidth?: number | string
  submitText?: string
}>(), {
  title: '',
  submitting: false,
  maxWidth: 600,
  submitText: '确定'
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit'): void
}>()

const appStore = useAppStore()
const isMobile = computed(() => appStore.device === 'mobile')
</script>
