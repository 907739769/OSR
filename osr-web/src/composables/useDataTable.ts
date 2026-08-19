import { ref, type Ref } from 'vue'

interface DataTableHost {
  /** 列表 composable 的 queryParams（至少含 pageNum / pageSize） */
  queryParams: { pageNum: number; pageSize: number; [key: string]: any }
  getList: () => void
  /** 选择变化回调（useTaskList / useRecordList 提供）。不带勾选的表格可以不传 */
  handleSelectionChange?: (rows: any[]) => void
}

/**
 * v-data-table-server 与列表 composable 之间的接线。
 *
 * 这四件事（承接选中行的本地 ref、把选中转交给 composable、翻页、换每页条数）原先在
 * 10 个 PC 列表页里逐字重复，每页约 20 行。它们没有一处是页面自己的判断——
 * 页面唯一需要决定的是表头怎么排。
 *
 * selectedRows 必须是本地 ref：v-data-table-server 的多选走 `:model-value` +
 * `return-object`，拿到的是整行对象，而 composable 侧只存 id。
 */
export function useDataTable(host: DataTableHost): {
  selectedRows: Ref<any[]>
  onSelectionChange: (rows: any[]) => void
  clearSelection: () => void
  onPageChange: (page: number) => void
  onSizeChange: (size: number) => void
} {
  const selectedRows = ref<any[]>([])

  const onSelectionChange = (rows: any[]) => {
    selectedRows.value = rows
    host.handleSelectionChange?.(rows)
  }

  /** 表格的 model 与 composable 侧派生的选中态一起清，漏一个就是「批量条没了、按钮还亮着」 */
  const clearSelection = () => onSelectionChange([])

  const onPageChange = (page: number) => {
    host.queryParams.pageNum = page
    host.getList()
  }

  /** 换每页条数要回到第 1 页：留在第 7 页按新页长算多半已经越界，界面上是一片空白 */
  const onSizeChange = (size: number) => {
    host.queryParams.pageSize = size
    host.queryParams.pageNum = 1
    host.getList()
  }

  return { selectedRows, onSelectionChange, clearSelection, onPageChange, onSizeChange }
}
