/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_APP_BASE_API: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module 'virtual:pwa-register' {
  export interface RegisterSWOptions {
    onNeedRefresh?: () => void
    onOfflineReady?: () => void
    onRegistered?: (registration: ServiceWorkerRegistration | undefined) => void
    onRegisterError?: (error: Error) => void
    registrationTimeout?: number
  }

  export function useRegisterSW(options?: RegisterSWOptions): {
    needRefresh: import('vue').Ref<boolean>
    offlineReady: import('vue').Ref<boolean>
    updateServiceWorker: (reloadPage?: boolean) => Promise<void>
  }
}

declare module 'virtual:pwa-register/vue' {
  export interface RegisterSWOptions {
    onNeedRefresh?: () => void
    onOfflineReady?: () => void
    onRegistered?: (registration: ServiceWorkerRegistration | undefined) => void
    onRegisterError?: (error: Error) => void
    registrationTimeout?: number
  }

  export function useRegisterSW(options?: RegisterSWOptions): {
    needRefresh: import('vue').Ref<boolean>
    offlineReady: import('vue').Ref<boolean>
    updateServiceWorker: (reloadPage?: boolean) => Promise<void>
  }
}
