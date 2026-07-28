import {
  Setting, Document, Picture, Monitor, Tools, Calendar, Coin, Promotion,
  Watermelon, Menu as IconMenu, VideoPlay, RefreshRight, EditPen,
  FolderOpened, DocumentCopy, MagicStick, Connection, Download, Film, Filter,
  TrendCharts, CircleClose
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

/**
 * 后端菜单存的是 Font Awesome 图标类名，前端用的是 Element Plus 图标组件，
 * 这里做一层映射。未收录的图标返回 undefined，由调用方决定兜底展示。
 */
const iconMap: Record<string, Component> = {
  'fa fa-gear': Setting,
  'fa fa-cog': Setting,
  'fa fa-bookmark-o': Document,
  'fa fa-sun-o': Picture,
  'fa fa-video-camera': Monitor,
  'fa fa-tasks': Tools,
  'fa fa-calendar': Calendar,
  'fa fa-picture-o': Picture,
  'fa fa-yen': Coin,
  'fa fa-send-o': Promotion,
  'fa fa-diamond': Watermelon,
  'fa fa-bars': IconMenu,
  'fa fa-list-ul': IconMenu,
  'fa fa-list': IconMenu,
  'fa fa-file-code-o': DocumentCopy,
  'fa fa-folder-open-o': FolderOpened,
  'fa fa-play-circle-o': VideoPlay,
  'fa fa-video-play': VideoPlay,
  'fa fa-copy': RefreshRight,
  'fa fa-edit': EditPen,
  'fa fa-magic': MagicStick,
  'fa fa-rss': Connection,
  'fa fa-download': Download,
  'fa fa-server': Film,
  'fa fa-sliders': Filter,
  'fa fa-bar-chart': TrendCharts,
  'fa fa-ban': CircleClose
}

export function getIconComponent(icon?: string): Component | undefined {
  if (!icon) return undefined
  return iconMap[icon]
}
