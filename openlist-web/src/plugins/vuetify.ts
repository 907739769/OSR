import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify, type ThemeDefinition } from 'vuetify'
import { zhHans } from 'vuetify/locale'

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
    'surface-variant': '#EDE7DD',
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
    'surface-variant': '#2A303D',
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
  icons: {
    defaultSet: 'mdi'
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
