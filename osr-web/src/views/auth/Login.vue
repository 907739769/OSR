<template>
  <div class="login-stage">
    <!-- 背景舞台。三层叠加，都是纯 CSS、只动 transform，不占主线程：
         极光（缓慢漂移的色团）→ 网格（技术感的骨架）→ 暗角（把视线收到中心）。
         装饰层一律 aria-hidden，读屏器不该念它们 -->
    <div class="login-aurora" aria-hidden="true">
      <span class="aurora-blob aurora-blob--amber" />
      <span class="aurora-blob aurora-blob--indigo" />
      <span class="aurora-blob aurora-blob--teal" />
    </div>
    <div class="login-grid" aria-hidden="true" />
    <div class="login-vignette" aria-hidden="true" />

    <!-- 登录卡强制走暗色主题：整个舞台是深色的，卡片跟随全局明暗的话，
         浅色用户会看到一张白卡糊在深色场景上。用 v-theme-provider 而不是
         手写覆盖 —— 里面的输入框/按钮全是 Vuetify 组件，让它们自己按暗色
         主题渲染，比逐个改颜色可靠得多，也不会漏掉聚焦态、错误态这些分支。

         `with-background` 必须留着，尽管这里并不想要它那层背景：VThemeProvider 的
         实现是 `if (!props.withBackground) return slots.default?.()` —— 不带它就
         **一个元素都不渲染**，只靠 inject 传主题。Vuetify 组件照样会把
         v-theme--osrDark 挂到自己身上、拿到正确的 CSS 变量，但 `color` 是继承属性，
         中间没有元素重新锚定，面板里的输入文字与图标就会一路从 .v-application
         （用户开浅色时是 osrLight）继承成近黑色，压在深色玻璃上几乎看不见。
         补上这一环的正是 .v-theme-provider 自带的那条 `color: rgb(var(--v-theme-on-background))`。
         深色系统的用户看不到这个 bug，浅色的用户一直在看 —— 别再改回 false。 -->
    <v-theme-provider theme="osrDark" with-background class="login-theme-scope">
      <div class="login-panel">
        <div class="login-brand">
          <div class="login-logo-ring" aria-hidden="true">
            <img src="/icons/android-chrome-192x192.png" alt="" class="login-logo" />
          </div>
          <h1 class="login-title">OpenList STRM Relay</h1>
        </div>

        <!-- @submit.prevent 不是可选的：VForm 的 onSubmit 在校验通过且事件未被
             preventDefault 时会调 formRef.submit() 走原生提交，那是一次整页刷新。
             按钮用 type="submit" 而不是 @click，两个字段里按回车才有反应 ——
             VBtn 默认渲染成 type="button"，而表单有两个以上文本控件且无提交按钮时
             浏览器不做隐式提交，于是此前在用户名框按回车是彻底没有动静的 -->
        <v-form ref="loginFormRef" class="login-form" @submit.prevent="handleLogin">
          <div class="login-field" style="--osr-i: 1">
            <v-text-field
              v-model="loginForm.username"
              placeholder="用户名"
              aria-label="用户名"
              autofocus
              prepend-inner-icon="user"
              variant="outlined"
              density="comfortable"
              autocomplete="username"
              :rules="[(v: any) => !!v || '请输入用户名']"
            />
          </div>
          <div class="login-field" style="--osr-i: 2">
            <v-text-field
              v-model="loginForm.password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="密码"
              aria-label="密码"
              prepend-inner-icon="lock"
              variant="outlined"
              density="comfortable"
              autocomplete="current-password"
              :rules="[(v: any) => !!v || '请输入密码']"
            >
              <!-- 用插槽塞一个 v-btn 而不是 append-inner-icon + @click:append-inner：
                   后者渲染出来是个裸 v-icon，鼠标能点、键盘 Tab 不到，而这颗按钮
                   恰恰是给"看不清自己输了什么"的人用的。VBtn 默认 type="button"，
                   放在表单里点它不会触发提交 -->
              <template #append-inner>
                <v-btn
                  :icon="showPassword ? 'eye-off' : 'eye'"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  variant="text"
                  density="comfortable"
                  size="small"
                  @click="showPassword = !showPassword"
                />
              </template>
            </v-text-field>
          </div>
          <div class="login-field" style="--osr-i: 3">
            <v-btn
              color="primary"
              size="large"
              block
              variant="flat"
              :loading="loading"
              class="login-btn"
              type="submit"
            >
              登 录
            </v-btn>
          </div>
        </v-form>
      </div>
    </v-theme-provider>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from '@/composables/useMessage'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const loginFormRef = ref<any>()
const loading = ref(false)
const showPassword = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const handleLogin = async () => {
  const form = loginFormRef.value
  if (!form) return
  const { valid } = await form.validate()
  if (!valid) return
  loading.value = true
  try {
    await userStore.login(loginForm)
    message.success('登录成功')
    const redirect = (route.query.redirect as string) || '/'
    // 必须 await：这次导航里守卫还要跑 getUserInfo + getRouters 两个请求才注册动态路由，
    // 不等它就会在 finally 里提前收掉 loading，那几百毫秒页面静止、按钮也不转了，
    // 看起来像"点了没反应"。导航自身失败也该落进下面的 catch 而不是变成未处理的 rejection
    await router.push(redirect)
  } catch {
    // 失败原因由 request 拦截器统一提示（会带上后端返回的具体原因，如「用户名或密码错误」），
    // 这里再弹一次只会叠出两条重复消息
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
/* 登录页是全站唯一「不跟随明暗主题」的页面，色值刻意写死（design-system.spec.ts
   的 ALLOW_LITERAL 里登记过这个文件）。理由：它是一个**时刻**而不是一个工作区，
   固定的深色放映厅调性比跟着主题变更能立住品牌；而且这里的色值本就不是语义色，
   是一幅画的配色，派生自主题反而会让画面在浅色下垮掉。 */

/* provider 那个元素的存在意义只有一条：给 color 一个重新锚定的地方（理由见模板里的注释）。
   display: contents 把它从布局里摘掉 —— 不这么写的话它会成为 .login-stage 的 flex 子项、
   顶替 .login-panel 的位置参与尺寸计算；同时没有盒子也就不会画出它自带的那层不透明背景，
   极光舞台照旧透上来。继承不受 display: contents 影响，这正是要的效果。 */
.login-theme-scope {
  display: contents;
}

.login-stage {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
  background: #080b11;
  isolation: isolate;
}

/* ---- 极光层 ---- */
.login-aurora {
  position: absolute;
  inset: -20%;
  z-index: 0;
  /* blur 放在容器上而不是每个色团上：三个子元素各自 blur 会开三个滤镜层，
     容器上一次搞定，滚动/缩放时的重绘成本低得多 */
  filter: blur(90px);
  opacity: 0.85;
}

.aurora-blob {
  position: absolute;
  display: block;
  border-radius: 50%;
  /* 只动 transform —— 动 left/top 每帧都要重排，而这是个 20 秒不停的动画 */
  will-change: transform;
}

.aurora-blob--amber {
  top: 6%;
  left: 8%;
  width: 46vw;
  height: 46vw;
  background: radial-gradient(circle, rgba(224, 165, 72, 0.55), transparent 68%);
  animation: aurora-drift-a 26s ease-in-out infinite;
}

.aurora-blob--indigo {
  top: 34%;
  right: 2%;
  width: 52vw;
  height: 52vw;
  background: radial-gradient(circle, rgba(84, 106, 176, 0.5), transparent 68%);
  animation: aurora-drift-b 32s ease-in-out infinite;
}

.aurora-blob--teal {
  bottom: -12%;
  left: 28%;
  width: 42vw;
  height: 42vw;
  background: radial-gradient(circle, rgba(58, 150, 140, 0.34), transparent 70%);
  animation: aurora-drift-c 38s ease-in-out infinite;
}

@keyframes aurora-drift-a {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  50% { transform: translate3d(12vw, 8vh, 0) scale(1.18); }
}

@keyframes aurora-drift-b {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1.1); }
  50% { transform: translate3d(-14vw, -10vh, 0) scale(0.92); }
}

@keyframes aurora-drift-c {
  0%, 100% { transform: translate3d(0, 0, 0) scale(0.95); }
  50% { transform: translate3d(8vw, -12vh, 0) scale(1.22); }
}

/* ---- 网格层 ----
   极光是有机的、网格是几何的，两者叠在一起才是「科技感」而不是「壁纸」。
   mask 让网格从中心向外淡出，避免在边缘形成一圈生硬的截断线。 */
.login-grid {
  position: absolute;
  inset: 0;
  z-index: 1;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse 80% 70% at 50% 45%, #000 20%, transparent 75%);
  -webkit-mask-image: radial-gradient(ellipse 80% 70% at 50% 45%, #000 20%, transparent 75%);
}

/* ---- 暗角 ---- */
.login-vignette {
  position: absolute;
  inset: 0;
  z-index: 2;
  background: radial-gradient(ellipse 70% 60% at 50% 45%, transparent 30%, rgba(4, 6, 10, 0.75) 100%);
}

/* ---- 登录面板 ---- */
.login-panel {
  position: relative;
  z-index: 3;
  width: 100%;
  max-width: 380px;
  padding: 40px 32px 34px;
  border-radius: var(--osr-radius-xl);
  /* 玻璃：半透明底 + 背景模糊 + 1px 亮环 + 顶部内高光。
     这四件事缺一不可 —— 只做前两件是「半透明方块」，
     加上亮环和高光才有「一块有厚度的玻璃」的读感 */
  background: rgba(20, 24, 33, 0.58);
  backdrop-filter: blur(24px) saturate(1.5);
  -webkit-backdrop-filter: blur(24px) saturate(1.5);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.09),
    inset 0 1px 0 rgba(255, 255, 255, 0.12),
    0 32px 64px -24px rgba(0, 0, 0, 0.9);
  animation: osr-scale-in var(--osr-dur-4) var(--osr-ease-out) both;
}

.login-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 28px;
  animation: osr-fade-up var(--osr-dur-4) var(--osr-ease-out) both;
  animation-delay: 80ms;
}

/* logo 外圈的呼吸光环。光环画在伪元素上、只动 opacity 与 scale，
   不碰 box-shadow —— 动 box-shadow 每帧都要重新做一次高斯模糊，
   是「动画看着卡」最常见的单一原因 */
.login-logo-ring {
  position: relative;
  display: grid;
  place-items: center;
  width: 66px;
  height: 66px;
  margin-bottom: 14px;

  &::before {
    content: '';
    position: absolute;
    inset: -6px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(224, 165, 72, 0.45), transparent 70%);
    animation: logo-breathe 3.6s var(--osr-ease-in-out) infinite;
  }
}

@keyframes logo-breathe {
  0%, 100% { opacity: 0.5; transform: scale(0.94); }
  50% { opacity: 1; transform: scale(1.08); }
}

.login-logo {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: var(--osr-radius-md);
}

/* 品牌区只有两层：图标 + 全称。原先是「图标 + OSR + OpenList STRM Relay」三层，
   而图标本身就是 OSR 标志（那三个字母画在里面），那个 30px 的大标题等于把同一个词
   又说了一遍，全称再说第三遍。留下的这一层既是标志又是名字，没有重复。
   渐变从原来的简称标题继承过来 —— 它是这一屏唯一的品牌色落点，不能跟着一起删掉。 */
.login-title {
  margin: 0;
  font-family: var(--osr-font-mono);
  font-size: var(--osr-fs-2xl);
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: 0.1em;
  /* 文字本身做渐变：纯白标题在玻璃上会显得很平，
     从暖白到琥珀的渐变把品牌色带到了视觉中心 */
  background: linear-gradient(135deg, #fff 0%, #f4d9a8 55%, #e0a548 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  /* text-indent 抵消末位字母后面多出来的那格字距，让标题真正居中 */
  text-indent: 0.1em;
  /* 19 个等宽字符，折行的标题比小一号的标题难看得多；窄屏降字号见下方媒体查询 */
  white-space: nowrap;
}

.login-form {
  display: flex;
  flex-direction: column;
}

/* 三个字段依次入场（--osr-i 在模板里逐个给），错位步长与全站入场一致。
   套一层 div 而不是把动画挂在 v-text-field 上：Vuetify 的字段在校验态切换时
   会改自身的 transform（抖动提示），两个 transform 打架的表现是校验时抽一下 */
.login-field {
  animation: osr-fade-up var(--osr-dur-4) var(--osr-ease-out) both;
  animation-delay: calc(140ms + var(--osr-i, 0) * 70ms);
}

.login-btn {
  margin-top: 4px;
  letter-spacing: 0.3em;
  text-indent: 0.3em;
  font-weight: 600;
  box-shadow: 0 8px 24px -8px rgba(224, 165, 72, 0.7);
  transition:
    box-shadow var(--osr-dur-2) var(--osr-ease-out),
    transform var(--osr-dur-1) var(--osr-ease-spring);

  &:hover {
    box-shadow: 0 10px 34px -8px rgba(224, 165, 72, 0.9);
  }
}

/* 输入框在玻璃上要压暗一点，否则 Vuetify 暗色主题的 field 背景与面板同色、
   看不出这是个可输入的区域 */
.login-panel :deep(.v-field) {
  background: rgba(8, 11, 17, 0.42);
  border-radius: var(--osr-radius-md);
}

.login-panel :deep(.v-field__outline) {
  --v-field-border-opacity: 0.16;
}

@media (max-width: 480px) {
  .login-panel {
    padding: 32px 22px 26px;
  }

  .login-title {
    font-size: var(--osr-fs-xl);
  }

  .login-grid {
    background-size: 40px 40px;
  }
}

/* 320px 这一档单独再降一级：标题是 19 个等宽字符的单行，
   18px + 0.1em 字距要占 232px，而这里的面板内宽只有 217px —— 不降的话
   它不会触发横向滚动条（nowrap 只是渗进内边距），只是左右两边各顶出去几个像素，
   看起来像没对齐。字距同时收窄，纯降字号还是差一口气。 */
@media (max-width: 360px) {
  .login-title {
    font-size: var(--osr-fs-lg);
    letter-spacing: 0.06em;
    text-indent: 0.06em;
  }
}

/* 装饰性的持续动画（极光漂移、光环呼吸）不受令牌层那套时长压缩的管辖 ——
   它们的时长是写死的秒数，不是 --osr-dur-*。这类「永不停止」的动画对
   前庭敏感人群影响最大，直接停掉而不是加速。 */
@media (prefers-reduced-motion: reduce) {
  .aurora-blob,
  .login-logo-ring::before {
    animation: none;
  }
}
</style>
