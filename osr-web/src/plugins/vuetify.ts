import 'vuetify/styles'
import { createVuetify, type ThemeDefinition } from 'vuetify'
import { zhHans } from 'vuetify/locale'
import { aliases, lucide } from './lucideIcons'

// 胶片琥珀 + 深空靛蓝：影视/流媒体管理系统调性的自定义主题
const osrLight: ThemeDefinition = {
  dark: false,
  colors: {
    primary: '#B4690E',
    secondary: '#3B4B6B',
    error: '#C0362C',
    success: '#3F8F5F',
    warning: '#C98A1E',
    info: '#4C6C93',
    background: '#F7F5F1',
    surface: '#FFFFFF',
    // surface-variant 必须是<深色>，且必须与 on-surface-variant 成对给全。
    // Vuetify 把这对变量当成「浮层」的配色（v-tooltip 的底与字直接用它们，
    // v-snackbar / v-chip / v-slider 也在用），默认 light 主题里是 #424242 配 #EEEEEE。
    // 早先这里只把 surface-variant 改成浅米色 #EDE7DD、没动 on-surface-variant，
    // 而自定义主题会与 Vuetify 默认 light 主题 deep merge（见 parseThemeOptions），
    // 于是它继承了默认的 #EEEEEE —— 浅米底配近白字，对比度 1.06:1，tooltip 上的字根本看不见。
    // 改这两个值前先看 styles/__tests__/design-system.spec.ts 里那条对比度断言。
    'surface-variant': '#2E3646',
    'on-surface-variant': '#F7F5F1',
    'on-primary': '#FFFFFF',
    'on-secondary': '#FFFFFF',
    'on-error': '#FFFFFF',
    'on-success': '#FFFFFF',
    'on-warning': '#1A1305',
    'on-info': '#FFFFFF',
    'on-background': '#1A1A1A',
    'on-surface': '#1A1A1A'
  }
}

const osrDark: ThemeDefinition = {
  dark: true,
  colors: {
    primary: '#E0A548',
    secondary: '#8A9BC2',
    error: '#E1685C',
    success: '#6BBF8C',
    warning: '#E3A947',
    info: '#7C9AC2',
    background: '#141821',
    surface: '#1C212C',
    // 同上。暗色这边坏得更彻底：surface-variant 被改成深色 #2A303D，而默认 dark 主题的
    // surface-variant 是浅灰 #c8c8c8、配的 on-surface-variant 是纯黑 —— 继承下来就是
    // 深蓝灰底配黑字。比浅色主题那个还难认，只是没人在暗色下试过 tooltip。
    // 值比 surface(#1C212C) 亮一档，让浮层能从卡片上「浮」起来。
    'surface-variant': '#39414F',
    'on-surface-variant': '#E8EAED',
    'on-primary': '#1A1305',
    'on-secondary': '#151A24',
    'on-error': '#1A1305',
    'on-success': '#0C1A11',
    'on-warning': '#1A1305',
    'on-info': '#101820',
    'on-background': '#E8EAED',
    'on-surface': '#E8EAED'
  }
}

export default createVuetify({
  theme: {
    defaultTheme: 'osrLight',
    themes: { osrLight, osrDark }
  },
  // 图标是 lucide 的 SVG 组件，按需引入（见 plugins/lucideIcons.ts）。原先是 @mdi/font
  // 的整包 webfont —— 浏览器实取 403KB、还被 SW 预缓存进去，而全站只用到一百多个图标。
  // aliases 必须一并给：Vuetify 内置组件（下拉箭头、勾选框、排序箭头、分页）走的是 $ 别名，
  // 用默认那份会去找 mdi 名，全站这些位置一起变空白
  icons: {
    defaultSet: 'lucide',
    aliases,
    sets: { lucide }
  },
  locale: {
    locale: 'zhHans',
    messages: { zhHans }
  },
  defaults: {
    VBtn: { rounded: 'lg' },
    VCard: { rounded: 'lg' },
    VTextField: { variant: 'outlined', density: 'comfortable' },
    VSelect: { variant: 'outlined', density: 'comfortable' },
    VTextarea: { variant: 'outlined', density: 'comfortable' }
  }
})
