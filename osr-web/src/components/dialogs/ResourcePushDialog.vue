<template>
  <FormDialogShell v-model="pushOpen" title="直接下载" submit-text="推送" :submitting="pushing" @submit="confirmPush">
    <div v-if="pushTarget" class="push-target">{{ pushTarget.title }}</div>

    <v-select
      v-model="downloaderId"
      :items="downloaders"
      item-title="name"
      item-value="id"
      label="下载器"
      :no-data-text="'没有可用的下载器（只做种的不列）'"
      class="mb-2"
    />

    <!-- 直接下载与订阅推送的区别必须当场说清：不说的话用户会以为它和订阅下的一样被追踪，
         等着在下载记录页看进度、等着 H&R 被照看，而这两件事都不会发生 -->
    <v-alert type="info" variant="tonal" density="compact" class="mb-2">
      不建下载记录：OSR 不追踪它的进度，不会出现在「下载记录」页。下载完成后按目录触发的同步 / STRM 照常生效。
      想被追踪请用「转为订阅」。
    </v-alert>
    <v-alert v-if="pushTarget?.hitAndRun" type="warning" variant="tonal" density="compact">
      该站点有 H&R 考核，而直接下载不会下发分享限制、也不会提醒考核进度，请自行保证做种时长。
    </v-alert>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import { usePageState } from '@/composables/pageStateContext'
import type { usePtResourceSearch } from '@/composables/usePtResourceSearch'

const {
  pushOpen, pushTarget, downloaders, downloaderId, pushing, confirmPush
} = usePageState<ReturnType<typeof usePtResourceSearch>>()
</script>

<style scoped>
.push-target {
  font-size: 13px;
  word-break: break-all;
  margin-bottom: 12px;
  color: var(--osr-text-secondary);
}
</style>
