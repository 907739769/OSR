<template>
  <a
    v-if="href"
    :href="href"
    target="_blank"
    rel="noopener noreferrer"
    :class="linkClass"
    :title="hint"
    :aria-label="hint"
    @click.stop
  >
    <template v-if="variant === 'tag'">TMDb</template>
    <template v-else-if="variant === 'text'">{{ text || tmdbId }}</template>
    <v-icon v-else icon="external-link" :size="size" />
  </a>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { tmdbUrl } from '@/composables/tmdbLink'

/**
 * 「去 TMDb 看一眼」外链。刮错了/订错了的第一反应就是这个动作，而在此之前它只有
 * 重命名明细一处有——订阅、体检、日历、选片弹窗、热门自动订阅日志手里同样握着
 * tmdbId，却只能把它当成一串数字显示出来。
 *
 * 信息不足（缺 id 或媒体类型不是 tv/movie）时**整个组件不渲染**，调用方不必自己判：
 * 链接拼不出来时留一个点不动的图标，比没有这个图标更让人困惑。
 *
 * 三种形态对应三类落点，不要再加第四种：
 *   variant="icon"（默认）卡片标题旁、列表行里，只有一个小图标，不与标题抢注意力
 *   variant="tag"         重命名明细那排识别结果标签里，与同排的细描边标签同形
 *   variant="text"        把 TMDb ID 本身变成链接（选片弹窗的 ID 列、日志的 ID 列）
 *
 * `@click.stop` 是必须的，不是防御性写法：订阅卡片在批量模式下整卡点击 = 选中，
 * 选片弹窗整行点击 = 选片——不拦住的话点一下 TMDb 会顺带改掉选择状态，
 * 而用户看到的是"我只是想看看这是哪部剧，怎么把选中的片子换了"。
 */
const props = withDefaults(
  defineProps<{
    tmdbId?: string | number | null
    /** `tv`/`movie`，大小写不敏感——PT 侧存大写、重命名侧存小写，归一在 tmdbLink.ts 里 */
    mediaType?: string | null
    /** 季号，传了就深链到季页面（0 是合法的，TMDb 上的 Specials）。电影恒忽略 */
    season?: number | string | null
    /**
     * 集号，**只接受 TMDb 口径的集号**（`tmdb_episode_number`）。本地季内相对号与
     * TMDb 主数据未必一致（长篇动画用绝对号），拿本地集号拼出来的深链会落到一个
     * TMDb 上不存在的集上——拿不准就别传，落到季页面同样解决问题。
     */
    episode?: number | string | null
    variant?: 'icon' | 'tag' | 'text'
    /** variant="text" 时的链接文案，不传则显示 tmdbId 本身 */
    text?: string | number | null
    /** variant="icon" 时的图标尺寸 */
    size?: number | string
  }>(),
  {
    tmdbId: null,
    mediaType: null,
    season: null,
    episode: null,
    variant: 'icon',
    text: null,
    size: 14
  }
)

const href = computed(() =>
  tmdbUrl({ tmdbId: props.tmdbId, mediaType: props.mediaType }, { season: props.season, episode: props.episode })
)

/* 标签形态直接复用 list.scss 的全局 `a.record-tag`，不在本组件里把那套细描边样式
   再写一遍——重写的表现是同一排标签里 TMDb 那个比邻居高矮差一两像素 */
const linkClass = computed(() => ['tmdb-link', `tmdb-link--${props.variant}`, ...(props.variant === 'tag' ? ['record-tag'] : [])])

/** 提示语要说清落点：落到季/集页面时用户点之前就知道会看到什么 */
const hint = computed(() => {
  if (props.mediaType && String(props.mediaType).toLowerCase() === 'movie') return '在 TMDb 上查看这部电影'
  if (props.episode !== null && props.episode !== undefined && props.episode !== '') return '在 TMDb 上查看这一集'
  if (props.season !== null && props.season !== undefined && props.season !== '') return '在 TMDb 上查看这一季'
  return '在 TMDb 上查看'
})
</script>

<style scoped lang="scss">
.tmdb-link {
  color: var(--osr-primary);
  text-decoration: none;
}

/* 图标形态：卡片标题旁的配角，默认压暗，悬停才亮起来——标题才是主角 */
.tmdb-link--icon {
  display: inline-flex;
  align-items: center;
  vertical-align: middle;
  color: var(--osr-text-secondary);
  transition: color var(--osr-dur-2) var(--osr-ease-out);

  &:hover {
    color: var(--osr-primary);
  }
}

.tmdb-link--text:hover {
  text-decoration: underline;
}
</style>
