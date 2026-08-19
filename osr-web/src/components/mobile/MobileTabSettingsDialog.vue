<template>
  <v-dialog v-model="model" width="92%" scrollable>
    <v-card title="自定义底栏">
      <v-card-subtitle class="pb-2">
        最多选 {{ MAX_TABS }} 个，第 5 个格子固定是「更多」
      </v-card-subtitle>
      <v-card-text class="tab-settings-body">
        <v-list density="compact">
          <v-list-item
            v-for="link in allLinks"
            :key="link.path"
            :title="link.title"
            :prepend-icon="link.icon"
            class="tab-option"
            @click="toggle(link.path)"
          >
            <template #append>
              <v-checkbox-btn
                :model-value="picked.includes(link.path)"
                :disabled="!picked.includes(link.path) && picked.length >= MAX_TABS"
                @click.stop="toggle(link.path)"
              />
            </template>
          </v-list-item>
        </v-list>
      </v-card-text>
      <v-card-actions>
        <v-btn variant="text" @click="restoreDefault">恢复默认</v-btn>
        <v-spacer />
        <v-btn variant="outlined" @click="model = false">取消</v-btn>
        <v-btn variant="flat" color="primary" @click="save">保存</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useMobileTabs, MAX_TABS } from '@/composables/useMobileTabs'

const model = defineModel<boolean>({ default: false })

const { tabs, allLinks, setTabs } = useMobileTabs()

/** 弹窗里的草稿，点「保存」才落到 localStorage —— 否则关掉弹窗等于已经改完了 */
const picked = ref<string[]>([])

watch(model, (open) => {
  if (open) picked.value = tabs.value.map((t) => t.path)
}, { immediate: true })

const toggle = (path: string) => {
  const i = picked.value.indexOf(path)
  if (i >= 0) picked.value.splice(i, 1)
  else if (picked.value.length < MAX_TABS) picked.value.push(path)
}

const save = () => {
  setTabs(picked.value)
  model.value = false
}

const restoreDefault = () => {
  setTabs(null)
  model.value = false
}
</script>

<style scoped lang="scss">
.tab-settings-body {
  max-height: 60vh;
  padding-top: 0;
}

.tab-option {
  min-height: 44px;
}
</style>
