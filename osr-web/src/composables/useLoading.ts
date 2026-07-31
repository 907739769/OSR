import { useFeedbackStore } from '@/stores/feedback'

// 对应 ElLoading.service()：loading.show() 返回可关闭的句柄
export const loading = {
  show(text?: string) {
    const store = useFeedbackStore()
    store.startLoading(text)
    return { close: () => store.stopLoading() }
  }
}

export function useLoading() {
  const store = useFeedbackStore()
  return {
    start: (text?: string) => store.startLoading(text),
    stop: () => store.stopLoading()
  }
}
