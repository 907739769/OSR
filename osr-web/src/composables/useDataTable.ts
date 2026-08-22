import { ref, type Ref } from 'vue'

/** Vuetify 表格的排序项：`order` 省略时按升序处理 */
export interface DataTableSortItem {
  key: string
  order?: boolean | 'asc' | 'desc'
}

/**
 * 每页条数档位。
 *
 * Vuetify 表格 footer 的默认档位末位是「全部」（value 为 -1），但它在本项目里
 * 名不副实：后端 `BaseController#selectPage` 收到 -1 会**收敛成 1000 条**
 * （记录表可达数万行，整表返回会让前端渲染卡死）。于是界面上写着「全部」、
 * 实际只回来 1000 条，而 total 又是真实总数——用户看到的是「选了全部却还在分页」，
 * 只能怀疑是不是漏数据了。所以这里把末档显式写成 1000，说到做到。
 *
 * 后端那条 -1 → 1000 的兜底保留着：它挡的是直接调接口、以及将来别处再传 -1 的情况。
 */
export const ITEMS_PER_PAGE_OPTIONS = [10, 25, 50, 100, 1000]

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
 * 这五件事（承接选中行的本地 ref、把选中转交给 composable、翻页、换每页条数、排序）
 * 原先在 10 个 PC 列表页里逐字重复，每页约 20 行。它们没有一处是页面自己的判断——
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
  sortBy: Ref<DataTableSortItem[]>
  onSortChange: (sort: DataTableSortItem[]) => void
  itemsPerPageOptions: number[]
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

  /**
   * 表头排序。
   *
   * v-data-table-server **只发事件、不自己排数据**（它拿到的本来就只有当前一页），
   * 所以不接这个事件的表现是：点表头箭头翻转、一行都不动。10 个 PC 列表页此前全是
   * 这个状态，只有定时任务页因为用的是客户端的 v-data-table 才「碰巧」能排。
   *
   * 排序落到 orderByColumn / isAsc 两个参数上，由后端 `BaseController#selectPage` 消费
   * （驼峰列名在那边转下划线）。因此**表头 key 不是数据库列的列必须标 `sortable: false`**
   * ——合成列（detail / config / fileInfo 这类把好几个字段拼成一格的）传过去就是一个
   * 不存在的列名，整页会变成 500，而用户只是点了一下表头。
   *
   * sortBy 由本 composable 持有并回填给表格（`:sort-by`）：排序参数在「重置」时是保留的
   * （见 resetQueryParams），受控之后箭头才不会和实际顺序对不上。
   */
  const sortBy = ref<DataTableSortItem[]>([])

  const onSortChange = (sort: DataTableSortItem[]) => {
    sortBy.value = sort || []
    const first = sortBy.value[0]
    if (first?.key) {
      host.queryParams.orderByColumn = first.key
      host.queryParams.isAsc = first.order === 'desc' ? 'desc' : 'asc'
    } else {
      // 取消排序（第三次点击）：删掉参数，回到后端的默认排序
      delete host.queryParams.orderByColumn
      delete host.queryParams.isAsc
    }
    // 换排序等于换了一份结果集，停在第 3 页看到的是新顺序的第 3 页，没有意义
    host.queryParams.pageNum = 1
    host.getList()
  }

  return {
    selectedRows,
    onSelectionChange,
    clearSelection,
    onPageChange,
    onSizeChange,
    sortBy,
    onSortChange,
    itemsPerPageOptions: ITEMS_PER_PAGE_OPTIONS
  }
}
