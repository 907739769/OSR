import { ref, watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 把页面内 tab 的选中项同步到 URL 的 `?tab=`：刷新停在原来的 tab，也能从别处直接深链到某个 tab。
 *
 * 用 `router.replace` 而不是 push——切 tab 不该在浏览器历史里留一条，否则「返回」要连按好几下
 * 才离开这个页面。只改 query 不会触发 `onBeforeRouteLeave`（离开的判据是路由记录变了），
 * 所以和页面上的「未保存修改」拦截互不干扰。
 */
export function useTabQuery<T extends string>(tabs: readonly T[], fallback: T): Ref<T> {
  const route = useRoute()
  const router = useRouter()
  const pick = (value: unknown): T =>
    (tabs as readonly unknown[]).includes(value) ? (value as T) : fallback

  const active = ref(pick(route.query.tab)) as Ref<T>

  watch(active, (tab) => {
    if (route.query.tab === tab) return
    router.replace({ query: { ...route.query, tab } })
  })
  // 浏览器前进 / 后退改了 query 时跟上
  watch(() => route.query.tab, (value) => {
    const tab = pick(value)
    if (tab !== active.value) active.value = tab
  })

  return active
}
