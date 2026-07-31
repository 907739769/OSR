import { ref, watch, type Ref } from 'vue'

/**
 * 对响应式值添加防抖，适用于搜索输入场景
 * @param source 监听的响应式数据源
 * @param delay 防抖延迟（毫秒），默认 300ms
 * @returns 防抖后的 ref 值
 */
export function useDebouncedRef<T>(source: Ref<T>, delay = 300): Ref<T> {
  const debouncedValue = ref(source.value) as Ref<T>
  let timer: ReturnType<typeof setTimeout>

  watch(source, (val) => {
    clearTimeout(timer)
    timer = setTimeout(() => {
      debouncedValue.value = val
    }, delay)
  })

  return debouncedValue
}

/**
 * 对回调函数添加防抖
 * @param fn 需要防抖的函数
 * @param delay 防抖延迟（毫秒），默认 300ms
 * @returns 防抖后的函数（附带 cancel 方法）
 */
export function useDebounce<T extends (...args: any[]) => any>(fn: T, delay = 300) {
  let timer: ReturnType<typeof setTimeout>

  const debouncedFn = (...args: Parameters<T>) => {
    clearTimeout(timer)
    return new Promise<ReturnType<T>>((resolve) => {
      timer = setTimeout(() => {
        resolve(fn(...args))
      }, delay)
    })
  }

  debouncedFn.cancel = () => {
    clearTimeout(timer)
  }

  return debouncedFn
}
