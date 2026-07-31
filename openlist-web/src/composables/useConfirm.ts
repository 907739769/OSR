import { useFeedbackStore, type ConfirmOptions } from '@/stores/feedback'

// 对应 ElMessageBox.confirm；参数改为对象形式(支持 daterange 等复杂内容排版)
export function confirm(options: ConfirmOptions | string, title?: string): Promise<void> {
  const opts: ConfirmOptions = typeof options === 'string' ? { message: options, title } : options
  return useFeedbackStore().openConfirm(opts)
}

export type { ConfirmOptions }
