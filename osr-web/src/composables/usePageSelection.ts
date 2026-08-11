import { ref, computed, type Ref } from 'vue'

/**
 * 列表页「勾选当前页记录」的单源实现，useTaskList / useRecordList 都内置它，
 * 业务 composable 不要再各写一份 toggleSelect / handleCardClick / clearSelection。
 *
 * 选择集是**跨页累加**的：翻页不会清空 selectedIds，因此全选/半选判定只看当前页
 * 的 id 是否都在集合里，取消全选也只摘掉当前页那批，不动其它页已选的项。
 *
 * @param list    当前页数据（useTaskList 的 taskList / useRecordList 的 recordList）
 * @param idField 主键字段名
 */
export function usePageSelection(list: Ref<any[]>, idField: string) {
  const selectedIds = ref<number[]>([])

  const pageIds = computed<number[]>(() => list.value.map((item: any) => item[idField]))

  const toggleSelect = (id: number) => {
    const idx = selectedIds.value.indexOf(id)
    if (idx > -1) {
      selectedIds.value.splice(idx, 1)
    } else {
      selectedIds.value.push(id)
    }
  }

  /**
   * 整卡点击切换选中。三处要放行：checkbox 自己（交给它的 click，避免切换两次）、
   * 卡片底部操作区（PC 的 .card-footer / 移动端的 .card-actions，点按钮不该顺手选中这张卡）。
   */
  const handleCardClick = (event: Event, id: number) => {
    const target = event.target as HTMLElement
    if (target.closest('.card-checkbox') || target.closest('.card-footer') || target.closest('.card-actions')) return
    toggleSelect(id)
  }

  const clearSelection = () => {
    selectedIds.value = []
  }

  /** 当前页是否全部已选（空列表不算全选，否则全选框会在空态下显示成勾上） */
  const isAllPageSelected = computed(() =>
    pageIds.value.length > 0 && pageIds.value.every((id) => selectedIds.value.includes(id))
  )

  /** v-checkbox 的 model-value 可能是 boolean | null，调用处统一转成 boolean 再传 */
  const toggleSelectAllPage = (checked: boolean) => {
    if (checked) {
      for (const id of pageIds.value) {
        if (!selectedIds.value.includes(id)) selectedIds.value.push(id)
      }
    } else {
      const current = new Set(pageIds.value)
      selectedIds.value = selectedIds.value.filter((id) => !current.has(id))
    }
  }

  return {
    selectedIds, toggleSelect, handleCardClick, clearSelection,
    isAllPageSelected, toggleSelectAllPage
  }
}
