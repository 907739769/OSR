/**
 * 媒体服务器类型的显示名，PC 卡片 / 移动端卡片 / 表单下拉三处共用。
 *
 * 此前这件事写了三遍，其中两遍是 `item.type === 'JELLYFIN' ? 'Jellyfin' : 'Emby'`——
 * 这个写法有个额外的毛病：**未知类型会显示成 Emby**。`MediaServerClientFactory` 的注释
 * 明说了「未来接入 Plex 等异构服务器时实现 IMediaServerClient 并注册为 Bean 即可」，
 * 那天一到，后端加一种类型、前端三处各改一遍，漏掉的那处不会报错，只会把 Plex 显示成 Emby。
 *
 * 现在新增类型只改这张表。认不出的类型原样显示，而不是假装它是 Emby。
 */
export const MEDIA_SERVER_TYPES = [
  { title: 'Emby', value: 'EMBY' },
  { title: 'Jellyfin', value: 'JELLYFIN' }
]

export function mediaServerTypeLabel(type?: string | null): string {
  if (!type) return '未知'
  return MEDIA_SERVER_TYPES.find(t => t.value === type)?.title ?? type
}
