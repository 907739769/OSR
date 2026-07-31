/* eslint-env node */
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true
  },
  extends: [
    'eslint:recommended',
    'plugin:vue/vue3-recommended',
    'plugin:@typescript-eslint/recommended',
    // 由 unplugin-auto-import 生成，声明 ref/computed/ElMessage 等自动导入的全局
    './.eslintrc-auto-import.json'
  ],
  parser: 'vue-eslint-parser',
  parserOptions: {
    parser: '@typescript-eslint/parser',
    ecmaVersion: 'latest',
    sourceType: 'module'
  },
  plugins: ['@typescript-eslint'],
  ignorePatterns: ['dist', 'dev-dist', 'node_modules', 'auto-imports.d.ts', 'components.d.ts'],
  rules: {
    // 后端接口返回结构未建模，业务代码里 any 用得较多，先不拦
    '@typescript-eslint/no-explicit-any': 'off',
    // 以 _ 开头的参数视为有意忽略
    '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_', caughtErrors: 'none' }],
    // 组件名多为 index.vue，按目录区分，不强制多词
    'vue/multi-word-component-names': 'off',
    // 以下为 vue3-recommended 里的主观排版约定：对既有代码全量重排会产生上百处
    // 与功能无关的改动、打乱 git blame，收益不足以抵消噪音，故关闭。
    'vue/attributes-order': 'off',
    'vue/max-attributes-per-line': 'off',
    'vue/singleline-html-element-content-newline': 'off',
    'vue/multiline-html-element-content-newline': 'off',
    'vue/html-self-closing': 'off',
    // Vuetify 的 v-data-table 用带点号的具名 slot（如 #item.status）区分列，
    // 这套 eslint-plugin-vue 版本不认识这种写法会误报，是已知限制不是真的语法错误
    'vue/valid-v-slot': 'off'
  }
}
