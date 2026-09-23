<template>
  <!-- 多根节点：卡片必须是 .card-grid 的直接子元素，才能吃到网格轨道、
       也才能让 useGridPageSize 在骨架阶段就量出列数 -->
  <div
    v-for="i in count"
    :key="i"
    class="osr-sk-card osr-sheen osr-skeleton"
    :class="{ 'osr-sk-card--poster': variant === 'poster' }"
    :style="{ '--osr-i': i - 1 }"
    role="status"
    aria-busy="true"
    :aria-label="i === 1 ? '加载中' : undefined"
  >
    <template v-if="variant === 'poster'">
      <span class="osr-bone osr-bone--block osr-sk-card__poster" />
      <div class="osr-sk-card__body">
        <div class="osr-sk-card__head">
          <span class="osr-bone osr-bone--title" :style="{ width: pick(i, TITLE) }" />
          <span class="osr-bone osr-bone--chip" style="margin-left: auto" />
        </div>
        <span class="osr-bone osr-bone--caption" :style="{ width: pick(i + 1, TEXT) }" />
        <span class="osr-bone" :style="{ width: pick(i + 2, TEXT) }" />
        <span class="osr-bone osr-sk-card__bar" style="width: 100%" />
        <span class="osr-bone osr-bone--caption" :style="{ width: pick(i + 3, TEXT) }" />
      </div>
    </template>

    <template v-else>
      <div class="osr-sk-card__head">
        <span v-if="selectable" class="osr-bone osr-bone--check" />
        <span class="osr-bone osr-bone--title" :style="{ width: pick(i, TITLE) }" />
        <span class="osr-bone osr-bone--chip" style="margin-left: auto" />
      </div>
      <div class="osr-sk-card__rows">
        <div v-for="r in rows" :key="r" class="osr-sk-card__row">
          <span class="osr-bone osr-bone--caption osr-sk-card__label" />
          <!-- 百分比宽度要相对「标签右边剩下的那段」算，直接挂在行上会连标签宽度一起算、顶出卡片 -->
          <span class="osr-sk-card__val">
            <span class="osr-bone" :style="{ width: pick(i + r, TEXT) }" />
          </span>
        </div>
      </div>
      <span v-if="variant === 'record'" class="osr-bone osr-sk-card__bar" style="width: 100%" />
      <div v-if="actions" class="osr-sk-card__foot">
        <span v-for="b in actions" :key="b" class="osr-bone osr-sk-card__action" />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * PC 卡片网格页的首屏骨架，直接放在 `.card-grid` 里，与真实卡片互斥：
 * `<SkeletonCardGrid v-if="loading && !list.length" :count="columns * 2" :rows="4" />`。
 * 已经有数据时的刷新不走这里——换成骨架会把用户正在看的内容整片抹掉，
 * 那种场景用网格顶部的细进度条。
 */
withDefaults(
  defineProps<{
    /** 卡片张数，一般取网格列数的两倍（一屏两行），列数由 useGridPageSize 的 columns 给 */
    count?: number
    /** card：通用卡；poster：带海报的订阅卡；record：下载记录卡（多一条进度条位） */
    variant?: 'card' | 'poster' | 'record'
    /** 明细行数，贴着页面真实卡片的 .card-row 行数走，切换时不跳版 */
    rows?: number
    /** 卡片底部的操作按钮个数，0 表示没有 .card-footer */
    actions?: number
    /** 卡片左上角是否有勾选框 */
    selectable?: boolean
  }>(),
  { count: 6, variant: 'card', rows: 3, actions: 2, selectable: false }
)

// 宽度按下标轮换而不是随机：随机的话每次重渲染骨头都在跳，读起来像在抖
const TITLE = ['58%', '44%', '66%', '50%', '38%']
const TEXT = ['72%', '48%', '86%', '60%', '40%', '66%']
const pick = (i: number, list: string[]) => list[i % list.length]
</script>
