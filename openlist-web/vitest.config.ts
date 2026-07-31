import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { fileURLToPath, URL } from 'node:url'

/**
 * 单元测试专用配置。
 * 不复用 vite.config.ts：那份配置带 PWA、自动导入等插件，在测试环境下只会制造噪音。
 * vuetify() 插件是必需的：它在编译期把模板里的 <v-xxx> 标签转成具体组件 import，
 * 只 app.use(vuetify) 并不会全局注册所有组件，缺了这个插件测试环境下所有 v-* 标签都无法解析。
 */
export default defineConfig({
  plugins: [vue(), vuetify({ autoImport: true })],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    globals: false,
    css: true,
    // 只收 src 下的 .spec.ts，避免把 Playwright 的 e2e 用例也跑进来
    include: ['src/**/*.spec.ts'],
    setupFiles: ['./vitest.setup.ts'],
    // vuetify 默认被当作外部依赖直接用 Node require 加载，跳过了 Vite 的 CSS 处理管线，
    // 导致按组件自动引入的 .css 文件报 ERR_UNKNOWN_FILE_EXTENSION；inline 后强制走 Vite 转换。
    server: {
      deps: {
        inline: ['vuetify']
      }
    }
  }
})
