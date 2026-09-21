<template>
  <div class="config-save-bar">
    <span class="save-bar-status" :class="{ 'save-bar-status--dirty': dirty }">
      {{ dirty ? '有未保存的修改' : '已全部保存' }}
    </span>
    <v-btn variant="outlined" :disabled="!dirty || saving" @click="emit('discard')">放弃修改</v-btn>
    <v-btn color="primary" :variant="dirty ? 'flat' : 'outlined'" :loading="saving" @click="emit('save')">
      保存
    </v-btn>
  </div>
</template>

<script setup lang="ts">
/**
 * 单页配置表单的吸底保存条（PT 过滤规则 / 洗版规则）。
 *
 * 必须放在 `v-card` **外面**、作为 `.page-container` 的直接子元素：卡片是 overflow: hidden，
 * 放在里面 sticky 会贴在那个不滚动的祖先上、跟着内容一起滚走（见前端 AGENTS 的 ANTI-PATTERNS）。
 * 原先保存按钮在二十来项表单的最底部，改完上面的字段要滚到底才能存；
 * 旁边那个「重置」其实是「丢掉修改、重新加载」，现在叫「放弃修改」，没改动时置灰。
 */
defineProps<{ dirty: boolean; saving: boolean }>()
const emit = defineEmits<{ save: []; discard: [] }>()
</script>

<style scoped>
.config-save-bar {
  position: sticky;
  bottom: 12px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  background: var(--osr-surface);
  box-shadow: var(--osr-shadow-md);
}

.save-bar-status {
  flex: 1;
  font-size: var(--osr-fs-sm);
  color: var(--osr-text-secondary);
}

.save-bar-status--dirty {
  color: rgb(var(--v-theme-warning));
}

/* 移动端底部有悬浮底栏，吸底位置要让开它（让位一律用 occupied，见 AGENTS「移动端外壳」） */
@media (max-width: 768px) {
  .config-save-bar {
    bottom: calc(var(--osr-mobile-tabbar-occupied) + 8px);
  }
}
</style>
