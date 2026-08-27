<!--
  页面级错误边界。

  改造前应用里一个 onErrorCaptured / app.config.errorHandler 都没有：任意一个页面组件
  在渲染或事件处理里抛出未捕获异常，Vue 会把**整棵组件树卸载**，用户看到的是一整块白屏，
  控制台里那行报错他既看不到也读不懂，唯一的自救手段是刷新——而刷新回到的是同一个页面，
  同一个错误。

  两条设计取向：

  1. **边界包在 router-view 外面、布局外壳里面**，不是包整个 App。顶栏、侧边栏、底部 tab
     必须活着，用户才能自己导航到别处去；把整页换掉的话，除了刷新还是没有出路。
  2. **路由一变就自动复位**。错误态属于"某个页面的这一次渲染"，跟着边界一直挂到下一次
     刷新是不对的——用户点到别的菜单，那个页面本来是好的，却会继续看到上一页的错误。

  `onErrorCaptured` 返回 false 阻止继续向上冒泡（否则会再触发一次 main.ts 里的
  app.config.errorHandler，同一个错误报两遍）。它捕不到的两类留给全局兜底：
  边界自身渲染时抛的错，以及路由懒加载 chunk 拉取失败（那发生在组件解析阶段，
  组件树里还没有这个边界，由 vue-router 的 onError 管）。
-->
<template>
  <div v-if="error" class="error-boundary">
    <v-icon icon="triangle-alert" size="40" color="error" />
    <div class="error-boundary__title">页面出错了</div>
    <div class="error-boundary__desc">
      这一页在渲染时抛出了异常，不影响其它页面。可以重试，或换一个页面继续。
    </div>
    <div class="error-boundary__message">{{ message }}</div>
    <div class="error-boundary__actions">
      <v-btn variant="flat" color="primary" prepend-icon="refresh-cw" @click="retry">重试</v-btn>
      <v-btn variant="outlined" @click="goHome">返回首页</v-btn>
    </div>
  </div>
  <!-- display: contents，不参与布局。存在的唯一理由是给插槽内容一个可换的 key：
       只把 error 置空的话出错的组件实例还在，Vue 复用它继续渲染，多半立刻再抛同一个错。 -->
  <div v-else :key="retryKey" class="error-boundary__content">
    <slot />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onErrorCaptured } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const error = ref<unknown>(null)
const message = ref('')
const retryKey = ref(0)

onErrorCaptured((err, _instance, info) => {
  error.value = err
  message.value = `${err instanceof Error ? err.message : String(err)}（${info}）`
  // 边界吞掉错误之后控制台就什么都没有了，排查时最需要的堆栈必须自己补一条。
  console.error('[ErrorBoundary]', info, err)
  return false
})

const reset = () => {
  error.value = null
  message.value = ''
}

const retry = () => {
  retryKey.value += 1
  reset()
}

watch(() => route.fullPath, reset)

const goHome = () => {
  router.push('/')
}
</script>

<style scoped lang="scss">
.error-boundary__content {
  display: contents;
}

.error-boundary {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px 24px;
  min-height: 320px;
  text-align: center;
}

.error-boundary__title {
  font-size: var(--osr-fs-xl);
  font-weight: 600;
  color: var(--osr-text-primary);
}

.error-boundary__desc {
  font-size: var(--osr-fs-sm);
  color: var(--osr-text-secondary);
  max-width: 420px;
}

.error-boundary__message {
  font-family: var(--osr-font-mono);
  font-size: var(--osr-fs-xs);
  color: var(--osr-text-secondary);
  background: var(--osr-surface-hover);
  border: 1px solid var(--osr-border-base);
  border-radius: var(--osr-radius-sm);
  padding: 8px 12px;
  max-width: 100%;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.error-boundary__actions {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}
</style>
