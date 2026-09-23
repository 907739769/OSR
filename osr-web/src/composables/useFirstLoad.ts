import { computed, ref, watch, type Ref } from 'vue'

/**
 * 「首屏加载」与「刷新」的区分，决定该给骨架屏还是细进度条。
 *
 * 首屏还什么都没有，给骨架让用户先看到页面的形状；之后的刷新手里已经有内容，
 * 换成骨架会把正在看的东西整片抹掉再重画一遍，只该给一根细进度条。
 *
 * 判据是「loading 落下过一次」，不是「数据为空」：搜出空结果之后再刷新不该退回骨架；
 * 表单页的数据也没有「空」可言（未加载时是一份默认值）。初值恒为「未完成」——
 * 有的 composable 的 loading 初值是 false、挂载后才置 true，按初值判的话首屏就判不出来了。
 * keep-alive 的页面组件实例留着，返回时不会重放骨架。
 */
export function useFirstLoad(loading: Ref<boolean>) {
  const settled = ref(false)
  watch(loading, (now, before) => {
    if (before && !now) settled.value = true
  })
  /** 首屏加载中：该显示骨架屏 */
  const firstLoading = computed(() => loading.value && !settled.value)
  /** 刷新中（已有内容）：该显示细进度条 */
  const refreshing = computed(() => loading.value && settled.value)
  return { firstLoading, refreshing }
}
