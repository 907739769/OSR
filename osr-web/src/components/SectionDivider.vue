<template>
  <div class="section-divider"><span><slot /></span></div>
</template>

<script setup lang="ts">
/**
 * 表单里的分节标题：一行粗体文字 + 延伸到行尾的细线。
 * PT 过滤规则与洗版规则两页原先各自复制一份这段样式。
 */
</script>

<style scoped>
.section-divider {
  display: flex;
  align-items: center;
  margin: 20px 0 16px;
  font-size: var(--osr-fs-md);
  font-weight: 600;
  color: var(--osr-text-primary);

  span {
    padding-right: 12px;
  }

  &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: var(--osr-border-light);
  }
}
</style>
