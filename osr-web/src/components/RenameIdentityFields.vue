<template>
  <v-text-field
    v-model="tmdbId"
    label="指定 TMDb ID（纠正作品识别）"
    placeholder="例如 79481"
    inputmode="numeric"
    maxlength="9"
    clearable
    :rules="[idRule]"
    hint="填写后固定使用此作品，失败重试也会保留；留空沿用已有绑定，没有绑定时自动识别。"
    persistent-hint
    class="mb-2"
  />
  <v-select
    v-model="mediaType"
    label="TMDb 作品类型"
    :items="[{ title: '电影', value: 'movie' }, { title: '电视剧', value: 'tv' }]"
    :disabled="!tmdbId"
    :rules="[typeRule]"
    class="mb-2"
  />
  <TmdbLink v-if="tmdbId && /^[1-9]\d{0,8}$/.test(tmdbId.trim())" :tmdb-id="tmdbId.trim()" :media-type="mediaType" variant="text" />
</template>

<script setup lang="ts">
const tmdbId = defineModel<string>('tmdbId', { required: true })
const mediaType = defineModel<string>('mediaType', { required: true })
const idRule = (value: string) => !value || /^[1-9]\d{0,8}$/.test(value.trim()) || '请输入有效的 TMDb 正整数 ID'
const typeRule = (value: string) => !tmdbId.value || ['movie', 'tv'].includes(value) || '请选择电影或电视剧'
</script>
