import { defineStore } from 'pinia'

export type FeedbackLevel = 'success' | 'error' | 'warning' | 'info'

export interface SnackbarItem {
  id: number
  text: string
  level: FeedbackLevel
  timeout: number
}

export interface ConfirmOptions {
  title?: string
  message: string
  confirmText?: string
  cancelText?: string
  type?: FeedbackLevel
}

interface ConfirmState extends Required<Omit<ConfirmOptions, 'type'>> {
  visible: boolean
  type: FeedbackLevel
  resolve: (() => void) | null
  reject: ((reason?: string) => void) | null
}

let snackbarSeq = 0

// 不依赖组件 setup 上下文的全局反馈状态：可在 src/api/request.ts、
// src/router/index.ts 等纯 ts 模块里直接调用 useFeedbackStore()。
export const useFeedbackStore = defineStore('feedback', {
  state: () => ({
    snackbars: [] as SnackbarItem[],
    confirmState: {
      visible: false,
      title: '提示',
      message: '',
      confirmText: '确定',
      cancelText: '取消',
      type: 'warning' as FeedbackLevel,
      resolve: null,
      reject: null
    } as ConfirmState,
    loadingCount: 0,
    loadingText: ''
  }),
  getters: {
    loading: (state) => state.loadingCount > 0
  },
  actions: {
    pushSnackbar(text: string, level: FeedbackLevel, timeout = 3000) {
      this.snackbars.push({ id: ++snackbarSeq, text, level, timeout })
    },
    dismissSnackbar(id: number) {
      this.snackbars = this.snackbars.filter((s) => s.id !== id)
    },
    openConfirm(options: ConfirmOptions): Promise<void> {
      return new Promise((resolve, reject) => {
        this.confirmState = {
          visible: true,
          title: options.title ?? '提示',
          message: options.message,
          confirmText: options.confirmText ?? '确定',
          cancelText: options.cancelText ?? '取消',
          type: options.type ?? 'warning',
          resolve,
          reject
        }
      })
    },
    resolveConfirm() {
      this.confirmState.resolve?.()
      this.confirmState.visible = false
    },
    rejectConfirm() {
      this.confirmState.reject?.('cancel')
      this.confirmState.visible = false
    },
    startLoading(text = '') {
      this.loadingCount++
      if (text) this.loadingText = text
    },
    stopLoading() {
      this.loadingCount = Math.max(0, this.loadingCount - 1)
      if (this.loadingCount === 0) this.loadingText = ''
    }
  }
})
