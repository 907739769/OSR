/**
 * 菜单图标。<b>sys_menu.icon 现在直接存 mdi 名</b>（见迁移 20260780），本函数原样透传，
 * 新建菜单不需要再来这里登记。
 * <p>
 * 下面那张 fa→mdi 字典是<b>兼容旧库用的</b>：历史上 icon 存的是 Font Awesome 类名
 * （从 RuoYi 继承），而本项目前端是 Vuetify、根本没引入 Font Awesome，只能靠字典翻译。
 * 代价是新建菜单要改两处、漏了不报错——sql/ 目录下 4 个 fix-menu-icon 迁移都是这么来的，
 * 「通知路由」又栽了一次，所以 20260780 把库里的值直接换成了 mdi 名。
 * 字典留着兜住升级路径上漏网的旧值，<b>不要再往里加新条目</b>。
 * </p>
 * <p>
 * 认不出的值退化成 {@link FALLBACK_ICON} 而不是返回 undefined：侧边栏用 v-if 包着
 * #prepend 插槽，返回 undefined 会让整个插槽不渲染，该菜单项比同级少一块图标缩进——
 * 用户的实际反馈是「根本没看到这个菜单」。「压根没配图标」仍返回 undefined，
 * 保持「不配就不显示」的语义。
 * </p>
 */

/** 未收录图标的兜底。与 useMenuLinks 里快捷入口的兜底保持一致 */
export const FALLBACK_ICON = 'mdi-menu'
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
  'fa fa-fire': 'mdi-star',
  'fa fa-arrow-circle-o-up': 'mdi-arrow-up-bold-circle-outline',
  'fa fa-heart': 'mdi-heart-outline',
  'fa fa-weixin': 'mdi-wechat',
  'fa fa-bell-o': 'mdi-bell-outline'
}

export function getIconComponent(icon?: string): string | undefined {
  if (!icon) return undefined
  // 新库直接存 mdi 名，原样用
  if (icon.startsWith('mdi-')) return icon
  // 旧库的 fa fa-* 走字典；连字典也没有的（如按钮权限那种占位值 '#'）退化成通用图标
  return iconMap[icon] || FALLBACK_ICON
}
