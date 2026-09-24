import { ref, computed } from 'vue'
import { getSubscriptionOwnersApi, type SubscriptionOwnerOption } from '@/api/openlist/ptSubscription'

/**
 * 订阅「归属」筛选的选项（订阅页与追剧日历共用）：我的 / 公共 / 管理员另有其余每个有订阅的用户。
 * <p>
 * **只有真有得选时才显示**（至少两类归属下有订阅）：单用户部署里全是「我的」或全是「公共」，
 * 摆一个只有一个有效选项的下拉只会让人困惑。取失败时当作没得选、不显示，不影响页面其余部分。
 */
export function useOwnerFilter() {
  const ownerOptions = ref<SubscriptionOwnerOption[]>([])

  const loadOwners = async () => {
    try {
      ownerOptions.value = (await getSubscriptionOwnersApi()) || []
    } catch (e) {
      console.error(e)
      ownerOptions.value = []
    }
  }

  const ownerFilterVisible = computed(() => ownerOptions.value.filter((o) => o.count > 0).length >= 2)

  /** 下拉项：数量为 0 的「公共」不列；「我的」恒在（新用户还没订阅时也能选到它） */
  const ownerItems = computed(() => ownerOptions.value
    .filter((o) => o.count > 0 || o.value === 'mine')
    .map((o) => ({ title: `${o.label}（${o.count}）`, value: o.value })))

  loadOwners()

  return { ownerItems, ownerFilterVisible, loadOwners }
}
