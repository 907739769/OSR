<template>
  <div class="osr-sk-form osr-skeleton" :class="{ 'osr-sk-form--dense': dense }" role="status" aria-busy="true" aria-label="加载中">
    <section v-for="s in sections" :key="s" class="osr-sk-form__section osr-sheen" :style="{ '--osr-i': s - 1 }">
      <span class="osr-bone osr-bone--title" :style="{ width: s % 2 ? '120px' : '96px' }" />
      <div class="osr-sk-form__fields">
        <div v-for="f in fields" :key="f" class="osr-sk-form__field">
          <span class="osr-bone osr-bone--caption" :style="{ width: LABELS[(s + f) % LABELS.length] }" />
          <span class="osr-bone osr-bone--input" />
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 设置 / 配置类页面的首屏骨架：若干小节，每节一个标题 + 一组「标签 + 输入框」。
 * 只在首次加载时整块替换表单；保存后的重载不要走这里，否则用户刚改完的表单会整块消失再出现。
 */
withDefaults(
  defineProps<{
    sections?: number
    /** 每节的字段数 */
    fields?: number
    /** 去掉外层内边距，用在已经有内边距的卡片 / 标签页里 */
    dense?: boolean
  }>(),
  { sections: 2, fields: 4, dense: false }
)

const LABELS = ['64px', '96px', '80px', '112px', '72px']
</script>
