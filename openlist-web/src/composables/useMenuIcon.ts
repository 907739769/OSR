/**
 * 后端菜单存的是 Font Awesome 图标类名，前端用的是 mdi 图标名(Vuetify VIcon)，
 * 这里做一层映射。未收录的图标返回 undefined，由调用方决定兜底展示。
 */
const iconMap: Record<string, string> = {
  'fa fa-gear': 'mdi-cog',
  'fa fa-cog': 'mdi-cog',
  'fa fa-bookmark-o': 'mdi-file-document-outline',
  'fa fa-sun-o': 'mdi-image-outline',
  'fa fa-video-camera': 'mdi-monitor',
  'fa fa-tasks': 'mdi-wrench-outline',
  'fa fa-calendar': 'mdi-calendar',
  'fa fa-picture-o': 'mdi-image-outline',
  'fa fa-yen': 'mdi-coin',
  'fa fa-send-o': 'mdi-send-outline',
  'fa fa-diamond': 'mdi-diamond-stone',
  'fa fa-bars': 'mdi-menu',
  'fa fa-list-ul': 'mdi-format-list-bulleted',
  'fa fa-list': 'mdi-format-list-bulleted',
  'fa fa-file-code-o': 'mdi-file-document-multiple-outline',
  'fa fa-folder-open-o': 'mdi-folder-open-outline',
  'fa fa-play-circle-o': 'mdi-play-circle-outline',
  'fa fa-video-play': 'mdi-play-circle-outline',
  'fa fa-copy': 'mdi-refresh',
  'fa fa-edit': 'mdi-pencil-outline',
  'fa fa-magic': 'mdi-auto-fix',
  'fa fa-rss': 'mdi-rss',
  'fa fa-download': 'mdi-download-outline',
  'fa fa-server': 'mdi-filmstrip',
  'fa fa-sliders': 'mdi-filter-outline',
  'fa fa-bar-chart': 'mdi-chart-line',
  'fa fa-ban': 'mdi-close-circle-outline',
  'fa fa-fire': 'mdi-star'
}

export function getIconComponent(icon?: string): string | undefined {
  if (!icon) return undefined
  return iconMap[icon]
}
