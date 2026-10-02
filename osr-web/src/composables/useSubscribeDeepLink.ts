import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 订阅页接收 `?subscribe=片名&mediaType=TV|MOVIE`：打开建订阅弹窗并搜好 TMDb（资源搜索页「转为订阅」跳过来）。
 *
 * 两条：**用 watch 而不是 onMounted**——订阅页开了 keep-alive，第二次从资源搜索页跳过来时 setup 不再跑；
 * **处理完立刻把这两个参数从地址栏抹掉**（`router.replace`），否则刷新一次就又弹一次弹窗。
 */
export function useSubscribeDeepLink(openSubscribeWith: (keyword: string, mediaType: 'TV' | 'MOVIE') => unknown) {
  const route = useRoute()
  const router = useRouter()
  watch(
    () => route.query.subscribe,
    (value) => {
      const keyword = typeof value === 'string' ? value.trim() : ''
      if (!keyword) return
      const mediaType = route.query.mediaType === 'MOVIE' ? 'MOVIE' : 'TV'
      const rest = { ...route.query }
      delete rest.subscribe
      delete rest.mediaType
      router.replace({ path: route.path, query: rest })
      openSubscribeWith(keyword, mediaType)
    },
    { immediate: true }
  )
}
