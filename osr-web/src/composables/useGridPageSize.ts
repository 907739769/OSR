import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

/** 每页至少放这么多张卡（窄屏一行只有一两张时兜底） */
const MIN_ITEMS = 12
/** 每页至少铺满这么多行（宽屏一行七八张时，只发 12 条会空掉大半屏） */
const MIN_ROWS = 3
/** 分页器给的档位：基准整行数的 1 / 2 / 4 倍 */
const SIZE_MULTIPLIERS = [1, 2, 4]

/**
 * 列表 composable 的首次加载时机开关。
 *
 * PC 端卡片网格页统一传 `{ autoLoad: false }`：每页条数要按网格实际列数取整到整行，
 * 列数得挂载后才量得准，先按兜底值发一次再改口是白跑一趟请求——首次加载改由
 * useGridPageSize 在 onMounted 里触发。移动端是单列，保持默认（true）。
 */
export interface ListLoadOptions {
  autoLoad?: boolean
}

/**
 * 卡片网格页的「整行」分页尺寸。
 *
 * `.card-grid` 是 `repeat(auto-fill, minmax(300px, 1fr))` 的响应式网格，列数随窗口宽度
 * 在 1~8 之间变化。固定每页 12 条时，宽屏（比如一行 7 张）最后一行只填一半——用户会把
 * 「没填满」读成「没有下一页」。这里按实际列数把每页条数取整到整行：最后一行要么是满的，
 * 要么这一页就是最后一页。
 *
 * 用户选的是**倍数**而不是绝对条数，所以窗口宽度变化时档位能跟着列数换算，
 * 不会出现「select 的值不在 items 里」而显示空白的情况（固定 [12,24,48] 就有这个毛病）。
 *
 * @param apply 页大小变化时的回调，页面在这里写回 queryParams 并重新拉第一页
 */
export function useGridPageSize(apply: (pageSize: number) => void) {
  /** 绑到 `.card-grid` 元素上，列数直接从它的实际布局读 */
  const gridRef = ref<HTMLElement>()
  const columns = ref(4)
  /** 当前档位在 SIZE_MULTIPLIERS 里的取值 */
  const multiplier = ref(1)

  /** 每页基准条数：列数的整数倍，且不少于 MIN_ROWS 行 / MIN_ITEMS 条 */
  const baseSize = computed(() => Math.max(MIN_ROWS, Math.ceil(MIN_ITEMS / columns.value)) * columns.value)
  const pageSize = computed(() => baseSize.value * multiplier.value)
  const pageSizeOptions = computed(() => SIZE_MULTIPLIERS.map(m => baseSize.value * m))

  /**
   * 数 grid-template-columns 的轨道数，而不是在 JS 里复刻 minmax(300px,1fr) + gap
   * 这套 CSS 常量——列宽、间距、断点将来在 list.scss 里怎么改都不用同步这里。
   * 浏览器给的是解析后的具体值（"304px 304px …"）；拿不到（jsdom、元素还没渲染）
   * 或者拿到的还是没展开的 repeat()/minmax() 时保持原值，不瞎猜。
   */
  function measureColumns(): number {
    const el = gridRef.value
    if (!el) return columns.value
    const tracks = getComputedStyle(el).gridTemplateColumns
    if (!tracks || tracks === 'none' || /repeat\(|minmax\(|auto-fill|auto-fit/.test(tracks)) {
      return columns.value
    }
    return Math.max(1, tracks.split(' ').filter(Boolean).length)
  }

  /** 重新量一次；页大小真的变了才回调，免得每次 resize 都打一发请求 */
  function sync() {
    const next = measureColumns()
    if (next === columns.value) return
    const before = pageSize.value
    columns.value = next
    if (pageSize.value !== before) apply(pageSize.value)
  }

  let timer: ReturnType<typeof setTimeout> | undefined
  const onResize = () => {
    clearTimeout(timer)
    timer = setTimeout(sync, 200)
  }

  /** 分页器换档：存倍数而不是绝对条数 */
  function setPageSize(size: number) {
    const next = Math.max(1, Math.round(size / baseSize.value))
    if (next === multiplier.value) return
    multiplier.value = next
    apply(pageSize.value)
  }

  onMounted(() => {
    // 首次一定要回调一次：页面把初次加载交给了这里（autoLoad: false），
    // 量不到列数就用默认值走兜底，绝不能出现「一次都没加载」
    columns.value = measureColumns()
    apply(pageSize.value)
    window.addEventListener('resize', onResize)
  })

  onBeforeUnmount(() => {
    clearTimeout(timer)
    window.removeEventListener('resize', onResize)
  })

  return { gridRef, columns, pageSize, pageSizeOptions, setPageSize }
}
