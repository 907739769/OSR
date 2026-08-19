<template>
  <!-- 卡片上「更多」按钮弹出的底部动作面板。8 个页面原先各写一份同构的
       v-bottom-sheet + v-card + .drawer-actions，配套的三行开关状态见 useActionSheet()。 -->
  <v-bottom-sheet v-model="model">
    <!-- target 为空时不渲染：面板内容普遍会读 target 上的字段 -->
    <v-card v-if="target" :title="title">
      <v-card-text>
        <div class="drawer-actions">
          <slot />
        </div>
      </v-card-text>
    </v-card>
  </v-bottom-sheet>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ target?: unknown; title?: string }>(), {
  target: undefined,
  title: '更多操作'
})

const model = defineModel<boolean>({ default: false })
</script>
