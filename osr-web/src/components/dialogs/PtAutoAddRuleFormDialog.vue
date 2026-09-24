<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="submitForm">
    <v-form ref="formRef">
      <v-text-field
        v-model="form.name"
        label="规则名称"
        placeholder="如：每周热门电影"
        :rules="toRuleFns(rules.name)"
        class="mb-3"
      />
      <FormField label="是否启用">
        <v-switch v-model="form.enabled" true-value="1" false-value="0" color="primary" hide-details />
      </FormField>
      <FormField label="媒体类型">
        <v-radio-group v-model="form.mediaType" inline hide-details>
          <v-radio label="电影" value="MOVIE" />
          <v-radio label="剧集" value="TV" :disabled="isFollowSource(form.source)" />
        </v-radio-group>
      </FormField>
      <v-select
        v-model="form.source"
        label="数据源"
        :items="SOURCE_OPTIONS"
        item-title="title"
        item-value="value"
        class="mb-3"
      />
      <template v-if="isRssSource(form.source)">
        <v-select
          :model-value="null"
          label="常用榜单"
          :items="DOUBAN_ROUTE_PRESETS"
          item-title="label"
          item-value="path"
          placeholder="选一个填入下方地址"
          persistent-hint
          hint="预设按 RSSHub 官方路由填写，你的实例版本不同的话直接改下方地址即可"
          class="mb-3"
          @update:model-value="applyRoutePreset"
        />
        <v-text-field
          v-model="form.sourceUrl"
          label="RSS 地址"
          placeholder="/douban/movie/weekly/movie_real_time_hotest"
          :rules="sourceUrlRules"
          persistent-hint
          hint="填路由路径则与「参数设置 → RSSHub 服务地址」拼接；填完整 http(s) 地址则直接使用"
          class="mb-3"
        />
        <v-alert type="info" variant="tonal" density="compact" class="mb-3">
          豆瓣条目要按标题搜 TMDb 才能建订阅，搜不到同名作品的会跳过并记进执行日志。
          榜单里电影剧集常混在一起，只有与上方「媒体类型」一致的才会被订阅。
        </v-alert>
      </template>
      <template v-if="isFollowSource(form.source)">
        <v-text-field
          v-model="form.sourceUrl"
          :label="form.source === 'TMDB_PERSON' ? 'TMDb 人物 ID 或链接' : 'TMDb 系列 ID 或链接'"
          :placeholder="form.source === 'TMDB_PERSON' ? 'https://www.themoviedb.org/person/287-brad-pitt' : 'https://www.themoviedb.org/collection/87359'"
          :rules="sourceUrlRules"
          persistent-hint
          hint="填纯数字 ID，或直接粘贴 TMDb 页面链接"
          class="mb-3"
        />
        <v-alert type="info" variant="tonal" density="compact" class="mb-3">
          只支持电影。{{ form.source === 'TMDB_PERSON'
            ? '只订此人担任导演、或演员表前 5 位的电影，且限最近一年内上映或尚未上映的。'
            : '系列里的电影全部订阅，之后出了续集下一轮会自动补上。' }}
          未上映的新片评分人数通常很少，下方「最低评分 / 评分人数」建议留空。
        </v-alert>
      </template>
      <v-select
        v-model="genreExcludeArr"
        label="排除类型"
        :items="genreOptions"
        item-title="label"
        item-value="id"
        multiple
        chips
        closable-chips
        clearable
        placeholder="不排除任何类型"
        class="mb-3"
      />
      <v-text-field
        v-model.number="form.minVoteAverage"
        type="number"
        label="最低评分"
        :min="0"
        :max="10"
        step="0.5"
        placeholder="不限"
        persistent-hint
        :hint="isRssSource(form.source) ? '按 TMDb 评分过滤（不是豆瓣评分），与其它数据源口径一致' : undefined"
        class="mb-3"
      />
      <v-text-field
        v-model.number="form.minVoteCount"
        type="number"
        label="最低评分人数"
        :min="0"
        placeholder="不限"
        class="mb-3"
      />
      <v-select
        v-if="form.source === 'TMDB_DISCOVER'"
        v-model="form.region"
        label="地区"
        :items="REGION_OPTIONS"
        item-title="label"
        item-value="code"
        clearable
        placeholder="不限地区"
        class="mb-3"
      />
      <v-text-field
        v-model.number="form.maxAddPerRun"
        type="number"
        label="单轮上限"
        :min="1"
        :max="50"
        class="mb-3"
      />
      <v-text-field
        v-model.number="form.intervalHours"
        type="number"
        label="执行间隔"
        :min="1"
        :max="720"
        suffix="小时"
        class="mb-3"
      />
      <v-select
        v-model="form.downloaderId"
        label="指定下载器"
        :items="downloaderOptions"
        item-title="name"
        item-value="id"
        clearable
        placeholder="空则用唯一启用的下载器"
      />
    </v-form>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import FormField from '@/components/FormField.vue'
import { usePageState } from '@/composables/pageStateContext'
import { toRuleFns } from '@/composables/formRules'
import {
  REGION_OPTIONS,
  SOURCE_OPTIONS,
  DOUBAN_ROUTE_PRESETS,
  isRssSource,
  isFollowSource,
  type usePtAutoAddRule
} from '@/composables/usePtAutoAddRule'

const {
  open, dialogTitle, submitLoading, formRef, form, rules, submitForm,
  genreOptions, genreExcludeArr, downloaderOptions
} = usePageState<ReturnType<typeof usePtAutoAddRule>>()

// RSS 地址只在豆瓣源下必填：选了这个源却不填地址的话，规则能保存、执行时静静地一条都拉不到，
// 而用户要翻到执行日志才看得见那句 warn
const sourceUrlRules = [
  (value: string) => (!isRssSource(form.value.source) || !!value) || 'RSS 地址不能为空',
  (value: string) => (!isFollowSource(form.value.source) || !!value) || '请填写 TMDb ID 或链接'
]

// 系列/人物来源只支持电影：选中时把媒体类型拨到电影，否则后端整条规则跳过
watch(() => form.value.source, (source) => {
  if (isFollowSource(source)) form.value.mediaType = 'MOVIE'
})

/** 预设下拉只负责把路径填进地址框，本身不参与提交（所以 model-value 恒为 null） */
const applyRoutePreset = (path: string | null) => {
  if (path) form.value.sourceUrl = path
}
</script>
