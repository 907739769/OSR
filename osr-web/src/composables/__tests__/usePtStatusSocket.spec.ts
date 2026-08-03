import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { message } from '@/composables/useMessage'

vi.mock('@/composables/useMessage', () => ({
  message: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn()
  }
}))

vi.mock('js-cookie', () => ({
  default: { get: vi.fn() }
}))

vi.mock('@/stores/user', () => ({
  useUserStore: vi.fn()
}))

import Cookies from 'js-cookie'
import { useUserStore } from '@/stores/user'
import { usePtStatusSocket } from '../usePtStatusSocket'

/** 用假定时器时 microtask 队列不会自动清空，多 tick 几次把 refreshTokenFn 的 promise 链跑完 */
async function flushPromises() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}

/** 捕获所有实例，测试里按需手动触发 onmessage/onclose（jsdom 不会真的发起网络连接） */
class MockWebSocket {
  static instances: MockWebSocket[] = []
  url: string
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  closed = false

  constructor(url: string) {
    this.url = url
    MockWebSocket.instances.push(this)
  }

  close() {
    this.closed = true
  }
}

describe('usePtStatusSocket', () => {
  let clearToken: any
  let refreshTokenFn: any
  let errorSpy: any

  beforeEach(() => {
    vi.useFakeTimers()
    MockWebSocket.instances = []
    ;(globalThis as any).WebSocket = MockWebSocket
    ;(Cookies.get as any).mockReturnValue('test-token')
    clearToken = vi.fn()
    refreshTokenFn = vi.fn().mockResolvedValue({ token: 'new-token' })
    ;(useUserStore as any).mockReturnValue({ clearToken, refreshTokenFn })
    errorSpy = message.error as any
    delete (window as any).location
    ;(window as any).location = { protocol: 'http:', host: 'localhost', href: '' }
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('connect() 建立连接，url 带上 token 且路径不带 /api 前缀', () => {
    const { connect, disconnect } = usePtStatusSocket({})
    connect()

    expect(MockWebSocket.instances.length).toBe(1)
    expect(MockWebSocket.instances[0].url).toBe('ws://localhost/websocket/pt/status?token=test-token')
    disconnect()
  })

  it('收到 download 消息时分发给 onDownload 回调', () => {
    const onDownload = vi.fn()
    const { connect, disconnect } = usePtStatusSocket({ onDownload })
    connect()

    MockWebSocket.instances[0].onmessage?.({
      data: JSON.stringify({ type: 'download', downloadId: 1, subId: 2, episode: 3, state: 'DOWNLOADING', progress: 0.5 })
    })

    expect(onDownload).toHaveBeenCalledWith({ type: 'download', downloadId: 1, subId: 2, episode: 3, state: 'DOWNLOADING', progress: 0.5 })
    disconnect()
  })

  it('收到 subscription 消息时分发给 onSubscription 回调', () => {
    const onSubscription = vi.fn()
    const { connect, disconnect } = usePtStatusSocket({ onSubscription })
    connect()

    MockWebSocket.instances[0].onmessage?.({
      data: JSON.stringify({ type: 'subscription', subId: 2, lastMatchTime: '2026-07-24 15:30:00' })
    })

    expect(onSubscription).toHaveBeenCalledWith({ type: 'subscription', subId: 2, lastMatchTime: '2026-07-24 15:30:00' })
    disconnect()
  })

  it('收到 unauthorized 时先尝试刷新 token，刷新成功后用新 token 重连而不是直接登出', async () => {
    const { connect } = usePtStatusSocket({})
    connect()
    const socket = MockWebSocket.instances[0]

    socket.onmessage?.({ data: 'unauthorized' })
    await flushPromises()

    expect(refreshTokenFn).toHaveBeenCalled()
    expect(clearToken).not.toHaveBeenCalled()
    expect(window.location.href).not.toBe('/login')
    expect(MockWebSocket.instances.length).toBe(2)

    // 换新 token 后的重连不应再受旧连接 onclose 的定时重连影响
    socket.onclose?.()
    vi.advanceTimersByTime(5000)
    expect(MockWebSocket.instances.length).toBe(2)
  })

  it('收到 unauthorized 且刷新 token 也失败时，才清 token、跳登录页且不再自动重连', async () => {
    refreshTokenFn.mockRejectedValue(new Error('refresh token expired'))
    const { connect } = usePtStatusSocket({})
    connect()
    const socket = MockWebSocket.instances[0]

    socket.onmessage?.({ data: 'unauthorized' })
    await flushPromises()

    expect(refreshTokenFn).toHaveBeenCalled()
    expect(clearToken).toHaveBeenCalled()
    expect(errorSpy).toHaveBeenCalled()
    expect(window.location.href).toBe('/login')

    socket.onclose?.()
    vi.advanceTimersByTime(5000)
    expect(MockWebSocket.instances.length).toBe(1)
  })

  it('普通断线 3 秒后自动重连', () => {
    const { connect, disconnect } = usePtStatusSocket({})
    connect()
    expect(MockWebSocket.instances.length).toBe(1)

    MockWebSocket.instances[0].onclose?.()
    expect(MockWebSocket.instances.length).toBe(1)

    vi.advanceTimersByTime(3000)
    expect(MockWebSocket.instances.length).toBe(2)
    disconnect()
  })

  it('disconnect() 关闭连接并清理重连定时器，之后不会再自动重连', () => {
    const { connect, disconnect } = usePtStatusSocket({})
    connect()
    const socket = MockWebSocket.instances[0]

    disconnect()

    expect(socket.closed).toBe(true)
    vi.advanceTimersByTime(5000)
    expect(MockWebSocket.instances.length).toBe(1)
  })

  it('组件挂载时自动 connect，卸载时自动 disconnect', () => {
    const TestComponent = defineComponent({
      setup() {
        usePtStatusSocket({})
        return () => h('div')
      }
    })
    const wrapper = mount(TestComponent)

    expect(MockWebSocket.instances.length).toBe(1)
    const socket = MockWebSocket.instances[0]

    wrapper.unmount()

    expect(socket.closed).toBe(true)
  })
})
