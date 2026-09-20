import { formatRelativeTime } from './relativeTime'

/**
 * 媒体服务器的连通状态展示。PC 与移动端两张卡片共用这一份判据。
 *
 * 媒体服务器是订阅「已入库」判定的唯一数据来源，而它挂掉的<b>全部</b>可见症状是
 * 「订阅进度不动」——配置页上原先一个信号都没有（三行写的是 类型 / 地址 / 创建时间）。
 * 状态由后端在对账真正用到这台服务器时被动写回，见 `MediaServerHealthRecorder`。
 *
 * 两端各写一份的代价在本项目里已经兑现过多次（失败原因配色、集号判据、标题归一化），
 * 漂移的表现一律是「同一台服务器在 PC 上显示正常、在手机上显示异常」这类无从追查的不一致。
 */
export interface MediaServerHealth {
  /** 传给 StatusChip 的语义色 */
  type: 'success' | 'error' | 'default'
  /** 徽章上的文案 */
  text: string
  /** 悬浮提示（移动端没有 hover，异常时的原因另有一行正文，不能只靠它） */
  detail: string
  /** 是否要在卡片上单独占一行把失败原因写出来 */
  showError: boolean
}

export interface MediaServerHealthSource {
  lastCheckOk?: string | null
  lastCheckTime?: string | null
  lastCheckError?: string | null
}

export function mediaServerHealth(item: MediaServerHealthSource): MediaServerHealth {
  const when = formatRelativeTime(item.lastCheckTime)

  if (item.lastCheckOk === '1') {
    return {
      type: 'success',
      text: when ? `正常 · ${when}` : '正常',
      detail: `最近一次对账查询成功：${item.lastCheckTime ?? ''}`,
      showError: false
    }
  }

  if (item.lastCheckOk === '0') {
    return {
      type: 'error',
      text: when ? `异常 · ${when}` : '异常',
      detail: `${item.lastCheckTime ?? ''} ${item.lastCheckError ?? ''}`.trim(),
      showError: true
    }
  }

  // null / undefined 不是「不通」，而是「还没被用到过」——刚添的服务器、或者库里一条订阅都没有。
  // 把两者混成一个红色徽章会让一台完全正常的新服务器看起来是坏的，判据同后端的 lastCheckFailed()
  return {
    type: 'default',
    text: '尚未使用',
    detail: '还没有订阅对账用到过这台服务器；可在编辑弹窗里点「测试连接」立即验证',
    showError: false
  }
}
