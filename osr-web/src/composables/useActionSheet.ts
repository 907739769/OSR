import { ref, type Ref } from 'vue'

/**
 * 移动端卡片「更多操作」底部面板的开关状态。
 *
 * 这三行（open / target / 打开时先记下目标行）原先在 8 个移动端页面里逐字重复，
 * 每个页面还各自在按钮上写一句 `xxxOpen = false` 收尾——漏写就是点完动作面板不关。
 * 用 run() 包一下，关闭这件事只写一次。
 */
export function useActionSheet<T = any>(): {
  sheetOpen: Ref<boolean>
  sheetTarget: Ref<T | null>
  openSheet: (row: T) => void
  closeSheet: () => void
  run: (fn: () => void) => void
} {
  const sheetOpen = ref(false)
  const sheetTarget = ref<T | null>(null) as Ref<T | null>

  const openSheet = (row: T) => {
    sheetTarget.value = row
    sheetOpen.value = true
  }

  const closeSheet = () => {
    sheetOpen.value = false
  }

  /** 执行一个动作并关闭面板。动作抛错时面板照样关（用户已经看到 confirm/报错弹窗了） */
  const run = (fn: () => void) => {
    try {
      fn()
    } finally {
      sheetOpen.value = false
    }
  }

  return { sheetOpen, sheetTarget, openSheet, closeSheet, run }
}
