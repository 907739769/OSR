/**
 * lucide 图标集（Vuetify 自定义 IconSet）。
 *
 * <b>本项目的图标名就是 lucide 的官方名</b>（kebab-case，如 `search`、`trash-2`），
 * 模板里写什么、`sys_menu.icon` 里存什么，都是这个名字，中间<b>没有翻译层</b>。
 *
 * 这一点是刻意的：历史上 `sys_menu.icon` 存 Font Awesome 类名、前端却是 Vuetify，
 * 只能靠 `useMenuIcon.ts` 里一张手写字典翻译，于是建菜单要改两处、漏了不报错也不告警，
 * 只是那个菜单没图标 —— sql/ 目录下 4 个 fix-menu-icon 迁移全是这么来的。
 * 迁到 lucide 时同样只做了一次性 codemod + 一条 SQL 迁移，<b>不要再引入 mdi→lucide 的
 * 运行时字典</b>，那等于把刚拆掉的坑原样挖回来。
 *
 * 认不出的名字退化成 {@link FALLBACK}，并在开发模式下 warn：返回 undefined 会让
 * 侧边栏那个 `v-if` 包着的 #prepend 插槽整个不渲染，该菜单项比同级少一块图标缩进，
 * 用户的实际反馈是「根本没看到这个菜单」—— 宁可显示一个问号，也要让它占住位置。
 */
import { h, type Component, type FunctionalComponent } from 'vue'
import type { IconAliases, IconProps, IconSet } from 'vuetify'
import {
  Activity, ArrowBigUp, ArrowDown, ArrowLeft, ArrowLeftRight, ArrowRight,
  ArrowUp, BadgeCheck, Ban, Bell, BellOff, BellRing,
  BookOpen, Bookmark, Bot, BrushCleaning, Calendar, Calendar1,
  CalendarDays, CalendarOff, ChartColumn, ChartLine, Check, ChevronDown,
  ChevronLeft, ChevronRight, ChevronUp, ChevronsLeft, ChevronsRight, ChevronsUpDown,
  Circle, CircleAlert, CircleArrowUp, CircleCheck, CircleDot, CirclePlay,
  CircleQuestionMark, CircleX, Clapperboard, ClipboardList, Clock, CloudDownload,
  CloudOff, CloudUpload, Coins, Command, Copy, CornerDownLeft,
  Database, Delete, Download, Ellipsis, Eye, EyeOff,
  File, FileCog, FilePen, FileSearch, FileText, FileVideoCamera,
  FileX, Files, Film, Flame, FlaskConical, Folder,
  FolderCog, FolderOpen, FolderSync, Funnel, Gem, Heart,
  History, Image, Inbox, Info, LayoutDashboard, List,
  ListChecks, LoaderCircle, Lock, LogOut, MapPin, Maximize,
  Menu, Minimize, Minus, Monitor, MonitorPlay, Moon,
  Network, Option, Palette, PanelLeftClose, Paperclip, Pause,
  Pencil, Pipette, Play, Plus, RefreshCw, Replace,
  Rss, Save, ScanSearch, ScrollText, Search, Send,
  Server, Settings, Settings2, ShieldCheck, SlidersHorizontal, Space,
  Square, SquareArrowUp, SquareCheck, SquareMinus, SquarePen, Star,
  StarHalf, Stethoscope, Sun, SunMoon, TextCursorInput, Timer,
  Trash2, TriangleAlert, Tv, User, UserRoundX, UserX,
  Video, Volume, Volume1, Volume2, VolumeX, WandSparkles,
  Wrench, X, Zap, ZoomIn
} from 'lucide-vue-next'

/**
 * 品牌图标。<b>lucide 官方不收 logo</b>（品牌图标已剥离到 simple-icons），而 Telegram
 * 与企业微信是本项目的两个通知渠道，图标本身就承担「这条通知走哪个渠道」的识别功能，
 * 换成通用的铃铛/气泡等于把这个信息丢掉。
 *
 * 因此这两个从 simple-icons 取官方路径内联进来，是<b>全站仅有的两个实心图标</b>——
 * 品牌标识按惯例就是实心的，描边版本认不出来。新增通知渠道时照抄这里，
 * 不要为了统一风格把 logo 改成描边。
 */
const brandIcon = (path: string): FunctionalComponent => {
  const cmp: FunctionalComponent = () =>
    h(
      'svg',
      {
        xmlns: 'http://www.w3.org/2000/svg',
        viewBox: '0 0 24 24',
        width: '100%',
        height: '100%',
        fill: 'currentColor',
        'aria-hidden': 'true'
      },
      [h('path', { d: path })]
    )
  return cmp
}

const BrandTelegram = brandIcon(
  'M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z'
)
const BrandWecom = brandIcon(
  'M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178A1.17 1.17 0 0 1 4.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178 1.17 1.17 0 0 1-1.162-1.178c0-.651.52-1.18 1.162-1.18zm5.34 2.867c-1.797-.052-3.746.512-5.28 1.786-1.72 1.428-2.687 3.72-1.78 6.22.942 2.453 3.666 4.229 6.884 4.229.826 0 1.622-.12 2.361-.336a.722.722 0 0 1 .598.082l1.584.926a.272.272 0 0 0 .14.047c.134 0 .24-.111.24-.247 0-.06-.023-.12-.038-.177l-.327-1.233a.582.582 0 0 1-.023-.156.49.49 0 0 1 .201-.398C23.024 18.48 24 16.82 24 14.98c0-3.21-2.931-5.837-6.656-6.088V8.89c-.135-.01-.27-.027-.407-.03zm-2.53 3.274c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982zm4.844 0c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.969-.982z'
)

/** 图标名 → 组件。只登记真正用到的，未登记的名字在打包时就不会被引入 */
const icons: Record<string, Component> = {
  'activity': Activity,
  'arrow-big-up': ArrowBigUp,
  'arrow-down': ArrowDown,
  'arrow-left': ArrowLeft,
  'arrow-left-right': ArrowLeftRight,
  'arrow-right': ArrowRight,
  'arrow-up': ArrowUp,
  'badge-check': BadgeCheck,
  'ban': Ban,
  'bell': Bell,
  'bell-off': BellOff,
  'bell-ring': BellRing,
  'book-open': BookOpen,
  'bookmark': Bookmark,
  'bot': Bot,
  'brush-cleaning': BrushCleaning,
  'calendar': Calendar,
  'calendar-1': Calendar1,
  'calendar-days': CalendarDays,
  'calendar-off': CalendarOff,
  'chart-column': ChartColumn,
  'chart-line': ChartLine,
  'check': Check,
  'chevron-down': ChevronDown,
  'chevron-left': ChevronLeft,
  'chevron-right': ChevronRight,
  'chevron-up': ChevronUp,
  'chevrons-left': ChevronsLeft,
  'chevrons-right': ChevronsRight,
  'chevrons-up-down': ChevronsUpDown,
  'circle': Circle,
  'circle-alert': CircleAlert,
  'circle-arrow-up': CircleArrowUp,
  'circle-check': CircleCheck,
  'circle-dot': CircleDot,
  'circle-play': CirclePlay,
  'circle-question-mark': CircleQuestionMark,
  'circle-x': CircleX,
  'clapperboard': Clapperboard,
  'clipboard-list': ClipboardList,
  'clock': Clock,
  'cloud-download': CloudDownload,
  'cloud-off': CloudOff,
  'cloud-upload': CloudUpload,
  'coins': Coins,
  'command': Command,
  'copy': Copy,
  'corner-down-left': CornerDownLeft,
  'database': Database,
  'delete': Delete,
  'download': Download,
  'ellipsis': Ellipsis,
  'eye': Eye,
  'eye-off': EyeOff,
  'file': File,
  'file-cog': FileCog,
  'file-pen': FilePen,
  'file-search': FileSearch,
  'file-text': FileText,
  'file-video-camera': FileVideoCamera,
  'file-x': FileX,
  'files': Files,
  'film': Film,
  'flame': Flame,
  'flask-conical': FlaskConical,
  'folder': Folder,
  'folder-cog': FolderCog,
  'folder-open': FolderOpen,
  'folder-sync': FolderSync,
  'funnel': Funnel,
  'gem': Gem,
  'heart': Heart,
  'history': History,
  'image': Image,
  'inbox': Inbox,
  'info': Info,
  'layout-dashboard': LayoutDashboard,
  'list': List,
  'list-checks': ListChecks,
  'loader-circle': LoaderCircle,
  'lock': Lock,
  'log-out': LogOut,
  'map-pin': MapPin,
  'maximize': Maximize,
  'menu': Menu,
  'minimize': Minimize,
  'minus': Minus,
  'monitor': Monitor,
  'monitor-play': MonitorPlay,
  'moon': Moon,
  'network': Network,
  'option': Option,
  'palette': Palette,
  'panel-left-close': PanelLeftClose,
  'paperclip': Paperclip,
  'pause': Pause,
  'pencil': Pencil,
  'pipette': Pipette,
  'play': Play,
  'plus': Plus,
  'refresh-cw': RefreshCw,
  'replace': Replace,
  'rss': Rss,
  'save': Save,
  'scan-search': ScanSearch,
  'scroll-text': ScrollText,
  'search': Search,
  'send': Send,
  'server': Server,
  'settings': Settings,
  'settings-2': Settings2,
  'shield-check': ShieldCheck,
  'sliders-horizontal': SlidersHorizontal,
  'space': Space,
  'square': Square,
  'square-arrow-up': SquareArrowUp,
  'square-check': SquareCheck,
  'square-minus': SquareMinus,
  'square-pen': SquarePen,
  'star': Star,
  'star-half': StarHalf,
  'stethoscope': Stethoscope,
  'sun': Sun,
  'sun-moon': SunMoon,
  'text-cursor-input': TextCursorInput,
  'timer': Timer,
  'trash-2': Trash2,
  'triangle-alert': TriangleAlert,
  'tv': Tv,
  'user': User,
  'user-round-x': UserRoundX,
  'user-x': UserX,
  'video': Video,
  'volume': Volume,
  'volume-1': Volume1,
  'volume-2': Volume2,
  'volume-x': VolumeX,
  'wand-sparkles': WandSparkles,
  'wrench': Wrench,
  'x': X,
  'zap': Zap,
  'zoom-in': ZoomIn,
  'brand-telegram': BrandTelegram,
  'brand-wecom': BrandWecom
}

/** 认不出的图标名退化到这个，而不是不渲染 */
const FALLBACK = 'circle-question-mark'

/**
 * Vuetify 内置组件用的别名（下拉箭头、勾选框、排序箭头、分页……）。
 * <b>一个都不能漏</b>：漏掉的那个别名在对应组件上表现为「图标位置空着」，
 * 不报错、不告警，而这些组件（v-select / v-checkbox / v-data-table）遍布全站。
 */
export const aliases: IconAliases = {
  collapse: 'chevron-up',
  complete: 'check',
  cancel: 'circle-x',
  close: 'x',
  delete: 'circle-x',
  clear: 'circle-x',
  success: 'circle-check',
  info: 'info',
  warning: 'circle-alert',
  error: 'circle-x',
  prev: 'chevron-left',
  next: 'chevron-right',
  checkboxOn: 'square-check',
  checkboxOff: 'square',
  checkboxIndeterminate: 'square-minus',
  delimiter: 'circle',
  sortAsc: 'arrow-up',
  sortDesc: 'arrow-down',
  expand: 'chevron-down',
  menu: 'menu',
  subgroup: 'chevron-down',
  dropdown: 'chevron-down',
  radioOn: 'circle-dot',
  radioOff: 'circle',
  edit: 'pencil',
  ratingEmpty: 'star',
  ratingFull: 'star',
  ratingHalf: 'star-half',
  loading: 'loader-circle',
  first: 'chevrons-left',
  last: 'chevrons-right',
  unfold: 'chevrons-up-down',
  file: 'paperclip',
  plus: 'plus',
  minus: 'minus',
  calendar: 'calendar',
  treeviewCollapse: 'chevron-down',
  treeviewExpand: 'chevron-right',
  tableGroupCollapse: 'chevron-down',
  tableGroupExpand: 'chevron-right',
  eyeDropper: 'pipette',
  upload: 'cloud-upload',
  color: 'palette',
  command: 'command',
  ctrl: 'chevron-up',
  space: 'space',
  shift: 'arrow-big-up',
  alt: 'option',
  enter: 'corner-down-left',
  arrowup: 'arrow-up',
  arrowdown: 'arrow-down',
  arrowleft: 'arrow-left',
  arrowright: 'arrow-right',
  backspace: 'delete',
  play: 'play',
  pause: 'pause',
  fullscreen: 'maximize',
  fullscreenExit: 'minimize',
  volumeHigh: 'volume-2',
  volumeMedium: 'volume-1',
  volumeLow: 'volume',
  volumeOff: 'volume-x',
  search: 'search'
}

export const lucide: IconSet = {
  component: (props: IconProps) => {
    const name = String(props.icon ?? '')
    const cmp = icons[name]
    if (!cmp && import.meta.env.DEV) {
      // eslint-disable-next-line no-console
      console.warn(`[icons] 未登记的图标名「${name}」，已退化成 ${FALLBACK}。请在 plugins/lucideIcons.ts 里补上，或改用已有的 lucide 名。`)
    }
    return h(props.tag, null, [h(cmp ?? icons[FALLBACK], { size: '100%' })])
  }
}
