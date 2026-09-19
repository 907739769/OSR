import { ref } from 'vue'
import { getHitokotoApi } from '@/api/openlist/hitokoto'

/** 一言接口请求失败时的备用文案 */
const FALLBACK_QUOTES = [
  '代码写得好，Bug就是少。',
  '生活明朗，万物可爱。',
  '愿你被这个世界温柔以待。',
  '不积跬步，无以至千里。',
  '心之所向，素履以往。'
]

function randomFallbackQuote(): string {
  return FALLBACK_QUOTES[Math.floor(Math.random() * FALLBACK_QUOTES.length)]
}

/**
 * 首页欢迎区的两块内容：日期与一言。PC 与移动端首页共用，
 * 此前两端各抄一份（连备用文案都是两份），改一端漏一端是「两端文案不同步」的静默漂移。
 *
 * 日期是 ref 而不是模块级常量：页面挂过午夜后要能靠 `refreshDate` 更新成新的一天。
 */
export function useDashboardHeader(logTag = 'Dashboard') {
  const weekdayText = ref('')
  const dateText = ref('')
  const quote = ref(randomFallbackQuote())

  function refreshDate() {
    const now = new Date()
    weekdayText.value = now.toLocaleDateString('zh-CN', { weekday: 'long' })
    dateText.value = now.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })
  }

  function loadQuote() {
    getHitokotoApi()
      .then((data) => {
        quote.value = data.from ? `${data.hitokoto} —— ${data.from}` : data.hitokoto
      })
      .catch((e) => {
        console.error(`[${logTag}] 每日一言加载失败:`, e)
      })
  }

  refreshDate()
  return { weekdayText, dateText, quote, refreshDate, loadQuote }
}
