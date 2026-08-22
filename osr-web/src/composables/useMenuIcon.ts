/**
 * 菜单图标。`sys_menu.icon` 直接存 <b>lucide 官方图标名</b>（kebab-case，如 `settings`、
 * `calendar-days`），本函数原样透传 —— <b>这里没有、也不要再有翻译字典</b>。
 *
 * 历史包袱：icon 最早存的是 Font Awesome 类名（从 RuoYi 继承），而前端是 Vuetify、
 * 根本没引入 Font Awesome，只能靠本文件里一张手写字典翻译。代价是建菜单要改两处、
 * 漏了不报错也不告警，只是那个菜单没图标 —— sql/ 下 4 个 fix-menu-icon 迁移全是这么来的。
 * 20260780 把库里的值换成 mdi 名、拆掉了 fa 字典；20260790 换成 lucide 名，
 * 顺带把认不出的名字改成在开发模式下 warn（见 plugins/lucideIcons.ts）。
 *
 * 认不出的值退化成 {@link FALLBACK_ICON} 而不是返回 undefined：侧边栏用 v-if 包着
 * #prepend 插槽，返回 undefined 会让整个插槽不渲染，该菜单项比同级少一块图标缩进——
 * 用户的实际反馈是「根本没看到这个菜单」。「压根没配图标」仍返回 undefined，
 * 保持「不配就不显示」的语义。
 */

/** 未收录图标的兜底。与 useMenuLinks 里快捷入口的兜底保持一致 */
export const FALLBACK_ICON = 'menu'

export function getIconComponent(icon?: string): string | undefined {
  if (!icon) return undefined
  // 旧库里可能还留着 fa fa-* / mdi-* 这类历史值（迁移漏网、或手工改过库）。
  // 不翻译，直接退化成通用图标——比显示一个错的图标更容易被发现并改对
  if (icon.startsWith('fa ') || icon.startsWith('mdi-') || icon === '#') return FALLBACK_ICON
  return icon
}
