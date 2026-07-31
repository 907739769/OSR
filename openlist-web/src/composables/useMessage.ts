import { useFeedbackStore, type FeedbackLevel } from '@/stores/feedback'

function show(text: string, level: FeedbackLevel, timeout?: number) {
  useFeedbackStore().pushSnackbar(text, level, timeout)
}

// 贴近 ElMessage 的调用签名，便于批量替换 import 路径
export const message = {
  success: (text: string) => show(text, 'success'),
  error: (text: string) => show(text, 'error', 5000),
  warning: (text: string) => show(text, 'warning'),
  info: (text: string) => show(text, 'info')
}
