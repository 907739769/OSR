<template>
  <div class="osr-sk-table osr-skeleton" :style="{ '--sk-cols': template }" role="status" aria-busy="true" aria-label="加载中">
    <div v-for="r in rows" :key="r" class="osr-sk-table__row osr-sheen" :style="{ '--osr-i': r - 1 }">
      <span v-if="selectable" class="osr-sk-table__cell">
        <span class="osr-bone osr-bone--check" />
      </span>
      <span v-for="(col, c) in cols" :key="c" class="osr-sk-table__cell" :class="`osr-sk-table__cell--${col.align}`">
        <template v-if="col.kind === 'actions'">
          <span v-for="b in 2" :key="b" class="osr-bone osr-bone--btn" />
        </template>
        <span v-else-if="col.kind === 'chip'" class="osr-bone osr-bone--chip" />
        <span v-else-if="col.kind === 'wide'" class="osr-sk-table__stack">
          <span class="osr-bone" :style="{ width: WIDTHS[(r + c) % WIDTHS.length] }" />
          <span class="osr-bone osr-bone--caption" :style="{ width: WIDTHS[(r + c + 3) % WIDTHS.length] }" />
        </span>
        <span v-else class="osr-bone" :style="{ width: WIDTHS[(r + c * 2) % WIDTHS.length] }" />
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 表格页的首屏骨架，放进 v-data-table 的 #loading 插槽：
 * `<template v-if="!list.length" #loading><SkeletonTable :headers="headers" selectable /></template>`
 *
 * **插槽必须带 `v-if="!list.length"`**。Vuetify 的规则是「loading 且（没数据 或 提供了
 * loading 插槽）」就用加载行替换全部数据行——无条件提供插槽的话，每次翻页、每次刷新都会
 * 把当前数据整片抹成骨架。带条件时只有首屏走骨架，有数据的刷新仍是表头那根细进度条。
 *
 * 列宽直接吃表格自己的 headers（width → 定宽、minWidth → 至少这么宽并分剩余空间），
 * 骨架的每一格因此落在真实列的位置上，数据到来时不跳版。
 */
interface HeaderLike {
  key?: string
  width?: string | number
  minWidth?: string | number
  align?: 'start' | 'center' | 'end'
}

const props = withDefaults(
  defineProps<{
    headers: readonly HeaderLike[]
    rows?: number
    /** 表格是否带 show-select 勾选列 */
    selectable?: boolean
  }>(),
  { rows: 8, selectable: false }
)

const WIDTHS = ['78%', '52%', '64%', '40%', '88%', '58%', '70%']

const px = (v: string | number) => (/^\d+$/.test(String(v)) ? `${v}px` : String(v))

const cols = computed(() =>
  props.headers.map((h) => {
    const key = h.key ?? ''
    let kind: 'actions' | 'chip' | 'wide' | 'text' = 'text'
    if (key === 'actions') kind = 'actions'
    // 状态列在真实表格里是 StatusChip，骨架里画成 pill，形状对得上
    else if (/status|state|enabled/i.test(key)) kind = 'chip'
    // 定了大最小宽度的是「详情 / 路径」这类合成列，真实内容是两行，骨架也给两行
    else if (h.minWidth && parseInt(String(h.minWidth), 10) >= 240) kind = 'wide'
    return { kind, align: h.align ?? 'start' }
  })
)

const template = computed(() =>
  [
    props.selectable ? '56px' : '',
    ...props.headers.map((h) => {
      if (h.width) return px(h.width)
      if (h.minWidth) return `minmax(${px(h.minWidth)}, 1fr)`
      return 'minmax(0, 1fr)'
    })
  ]
    .filter(Boolean)
    .join(' ')
)
</script>

<style scoped lang="scss">
.osr-sk-table__row {
  display: grid;
  grid-template-columns: var(--sk-cols);
  align-items: center;
  min-height: 60px;
  border-bottom: 1px solid var(--osr-border-light);
}

.osr-sk-table__cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 0 16px;

  &--center {
    justify-content: center;
  }

  &--end {
    justify-content: flex-end;
  }

  /* 定宽的列里骨头按比例取宽会太短，给个下限 */
  > .osr-bone:not(.osr-bone--chip, .osr-bone--btn, .osr-bone--check) {
    min-width: 40px;
  }
}

.osr-sk-table__stack {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}
</style>
