import type { SearchParams } from '@/types'

/**
 * 把查询条件还原成默认值。
 *
 * 原来的重置是 `queryRef.value?.reset?.()`，靠 Vuetify 的 v-form 反向清空输入框，
 * 三个地方靠不住，所以改成在 composable 里按默认值快照还原：
 * 1. 页面漏写 `ref="queryRef"` 时（PT 订阅、PT 媒体服务器，以及全部走 MobileSearchPanel
 *    的移动端页面都漏了），`queryRef.value` 是 undefined，可选链直接吃掉调用，
 *    重置按钮变成"只重新查一遍"，条件一个都不变；
 * 2. v-form.reset() 是把注册在表单里的输入框置为 null，而不是还原默认值——
 *    订阅页 `status: 'ACTIVE'`、孤儿页 `status: '0'` 这类非空默认值会被清成"全部"；
 * 3. 没渲染成表单控件的条件（路由带进来的 subId、日期区间转出来的 params 等）它管不到。
 *
 * pageSize 不还原：它由分页器控制，属于用户的浏览偏好而非筛选条件，
 * 点"重置"时把每页条数跳回默认值反而突兀。pageNum 由调用方的 handleQuery 归 1。
 *
 * orderByColumn / isAsc 同理不还原，而且这里还多一层理由：表头的排序箭头是
 * v-data-table-server 自己的状态（`:sort-by` 由 useDataTable 持有），重置动不到它。
 * 清掉排序参数的话，箭头还指着"创建时间升序"、数据却已经按默认序回来了，
 * 比"重置没清排序"更难解释。
 */
export function resetQueryParams(queryParams: SearchParams, defaults: SearchParams) {
  const pageSize = queryParams.pageSize
  const { orderByColumn, isAsc } = queryParams
  // 默认值里没有的键（页面自行挂上去的筛选字段、日期区间写入的 params）直接删掉，
  // 只做 Object.assign 的话它们会残留下来继续参与查询
  Object.keys(queryParams).forEach(key => {
    if (!(key in defaults)) delete (queryParams as Record<string, any>)[key]
  })
  Object.assign(queryParams, defaults)
  queryParams.pageSize = pageSize
  if (orderByColumn) {
    queryParams.orderByColumn = orderByColumn
    queryParams.isAsc = isAsc
  }
}
