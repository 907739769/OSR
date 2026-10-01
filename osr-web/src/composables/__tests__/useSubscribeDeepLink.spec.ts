import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'

const route = reactive<{ path: string; query: Record<string, any> }>({ path: '/openlist/ptSubscription', query: {} })
const replace = vi.fn()
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ replace }) }))

const { useSubscribeDeepLink } = await import('@/composables/useSubscribeDeepLink')

/**
 * 资源搜索页「转为订阅」的落点。两条：订阅页开了 keep-alive，所以是 watch 不是 onMounted；
 * 处理完要把参数从地址栏抹掉，否则刷新一次就再弹一次建订阅弹窗。
 */
describe('useSubscribeDeepLink', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    route.query = {}
  })

  it('带着片名进来：打开建订阅弹窗，并抹掉地址栏里的这两个参数、保留其余', () => {
    route.query = { subscribe: ' 三体 ', mediaType: 'TV', id: '3' }
    const open = vi.fn()

    useSubscribeDeepLink(open)

    expect(open).toHaveBeenCalledWith('三体', 'TV')
    expect(replace).toHaveBeenCalledWith({ path: '/openlist/ptSubscription', query: { id: '3' } })
  })

  it('mediaType 只认 MOVIE，其余一律按剧集', () => {
    route.query = { subscribe: 'Dune', mediaType: 'MOVIE' }
    const open = vi.fn()
    useSubscribeDeepLink(open)
    expect(open).toHaveBeenCalledWith('Dune', 'MOVIE')

    route.query = { subscribe: 'X', mediaType: 'whatever' }
    const open2 = vi.fn()
    useSubscribeDeepLink(open2)
    expect(open2).toHaveBeenCalledWith('X', 'TV')
  })

  it('没有 subscribe 参数时什么都不做', () => {
    const open = vi.fn()

    useSubscribeDeepLink(open)

    expect(open).not.toHaveBeenCalled()
    expect(replace).not.toHaveBeenCalled()
  })

  it('keep-alive 下第二次跳过来同样生效', async () => {
    const open = vi.fn()
    useSubscribeDeepLink(open)

    route.query = { subscribe: '漫长的季节', mediaType: 'TV' }
    await Promise.resolve()

    expect(open).toHaveBeenCalledWith('漫长的季节', 'TV')
  })
})
