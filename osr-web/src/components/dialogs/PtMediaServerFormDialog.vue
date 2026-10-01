<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="submitForm">
    <v-form ref="formRef">
      <v-text-field
        v-model="form.name"
        label="名称"
        placeholder="请输入名称"
        :rules="toRuleFns(rules.name)"
        class="mb-2"
      />
      <v-select
        v-model="form.type"
        label="类型"
        :items="MEDIA_SERVER_TYPES"
        class="mb-2"
      />
      <v-text-field
        v-model="form.url"
        label="服务器地址"
        :placeholder="isPlex(form.type) ? '如 http://192.168.1.10:32400' : '如 http://192.168.1.10:8096'"
        :rules="toRuleFns(rules.url)"
        class="mb-2"
      />
      <v-text-field
        v-model="form.apiKey"
        :label="isPlex(form.type) ? 'X-Plex-Token' : 'API Key'"
        type="password"
        :placeholder="form.id ? '留空则不修改' : isPlex(form.type) ? '请输入服务器的 Plex Token' : '请输入 API Key'"
        :rules="apiKeyRules"
        class="mb-2"
      />

      <!-- 用户ID 保持可手填：拉取要管理员权限、也可能因为服务器不通而失败，
           那时输入框仍是唯一的出路。下面的列表只是省掉「去 Web 控制台 URL 里抠一串
           32 位十六进制」这一步，不取代它 -->
      <!-- Plex 没有「按用户查询」：托管用户要走 plex.tv 换 token，这里一律用服务器 token 全库查 -->
      <v-text-field
        v-if="!isPlex(form.type)"
        v-model="form.userId"
        label="用户ID"
        placeholder="留空则按服务器全库查询"
        hint="可点右侧按钮从媒体服务器拉取用户列表"
        persistent-hint
        class="mb-1"
      >
        <template #append-inner>
          <v-btn
            :loading="usersLoading"
            variant="text"
            size="small"
            icon="users"
            :title="'拉取用户列表'"
            @click.stop="handleLoadUsers"
          />
        </template>
      </v-text-field>

      <div v-if="users.length && !isPlex(form.type)" class="user-picks mb-2">
        <v-chip
          size="small"
          variant="tonal"
          :color="!form.userId ? 'primary' : undefined"
          @click="form.userId = undefined"
        >
          全库查询
        </v-chip>
        <v-chip
          v-for="u in users"
          :key="u.id"
          size="small"
          variant="tonal"
          :color="form.userId === u.id ? 'primary' : undefined"
          :title="u.id"
          @click="form.userId = u.id"
        >
          {{ u.name || u.id }}
        </v-chip>
      </div>

      <v-radio-group v-model="form.enabled" inline label="状态" hide-details>
        <v-radio label="启用" value="1" />
        <v-radio label="停用" value="0" />
      </v-radio-group>

      <v-switch
        v-model="form.libraryNotify"
        true-value="1"
        false-value="0"
        color="primary"
        inset
        label="新文件入库后通知刷新媒体库"
        hint="STRM 生成、重命名、刮削写完文件，或清理产物删掉文件后，通知这台服务器只扫描对应目录，不用等它的定时扫描"
        persistent-hint
        class="mt-2 mb-2"
      />

      <template v-if="form.libraryNotify === '1'">
        <!-- 两边挂载路径不同是常态（OSR 里的 /data/media 在媒体服务器里是 /media），
             而映射不对时媒体服务器照样回成功、什么都不做，所以紧跟着给一个「检查」按钮 -->
        <v-textarea
          v-model="form.pathMapping"
          label="路径映射"
          :placeholder="isPlex(form.type) ? '/data/media => /data/media' : '/data/media => /media'"
          hint="每行一条「OSR 路径 => 媒体服务器路径」，按最长前缀匹配；两边挂载路径一致时留空"
          persistent-hint
          rows="2"
          auto-grow
          class="mb-2 mapping-input"
        />
        <v-btn
          size="small"
          variant="tonal"
          prepend-icon="scan-search"
          :loading="mappingCheckLoading"
          @click="handleCheckMapping"
        >
          检查路径映射
        </v-btn>

        <div v-if="mappingCheck" class="mapping-check mt-2">
          <div v-for="row in mappingCheck.rows" :key="row.localPath" class="mapping-row">
            <v-icon :icon="verdict(row).icon" :color="verdict(row).color" size="16" class="mapping-icon" />
            <div class="mapping-text">
              <div class="mapping-source">
                {{ row.source }}<code>{{ row.localPath }}</code>
              </div>
              <div class="mapping-verdict" :class="`text-${verdict(row).color}`">{{ verdict(row).text }}</div>
            </div>
          </div>
          <div class="mapping-libs">
            <template v-if="mappingCheck.libraries.length">
              媒体服务器上的媒体库：
              <span v-for="lib in mappingCheck.libraries" :key="lib.path" class="mapping-lib">
                {{ lib.name }} <code>{{ lib.path }}</code>
              </span>
            </template>
            <template v-else>媒体服务器上还没有建任何媒体库</template>
          </div>
        </div>
      </template>
    </v-form>

    <template #extra>
      <v-btn :loading="testLoading" variant="outlined" @click="handleTest">测试连接</v-btn>
    </template>
  </FormDialogShell>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import { usePageState } from '@/composables/pageStateContext'
import { toRuleFns } from '@/composables/formRules'
import { MEDIA_SERVER_TYPES, isPlex } from '@/composables/mediaServerTypes'
import type { usePtMediaServer } from '@/composables/usePtMediaServer'
import type { MappingCheckRow } from '@/api/openlist/ptMediaServer'

const {
  open, dialogTitle, submitLoading, formRef, form, rules, submitForm,
  testLoading, handleTest,
  users, usersLoading, handleLoadUsers,
  mappingCheck, mappingCheckLoading, handleCheckMapping
} = usePageState<ReturnType<typeof usePtMediaServer>>()

/**
 * 一个目录的检查结论。「不在库下」不一定是错的：STRM 输出目录常常只是重命名的来源，
 * 本就不该进媒体库，所以只用警告色、把两种可能都写出来，不用红色吓人
 */
function verdict(row: MappingCheckRow): { icon: string; color: string; text: string } {
  const via = row.rule ? `按「${row.rule}」映射为 ${row.mappedPath}` : `按原路径 ${row.mappedPath}`
  if (row.libraryName) {
    return { icon: 'circle-check', color: 'success', text: `在媒体库「${row.libraryName}」下（${via}）` }
  }
  if (row.nestedLibraries.length) {
    const names = row.nestedLibraries.map(n => `「${n}」`).join('')
    return { icon: 'circle-check', color: 'success', text: `${via}；其下的${names}是媒体库，写进那里的文件会通知到` }
  }
  return {
    icon: 'triangle-alert',
    color: 'warning',
    text: `${via}，不在任何媒体库下，不会通知。若它是媒体库目录，请检查映射；若只是重命名的来源目录，可忽略`
  }
}

// 编辑时后端出于安全考虑会把 apiKey 脱敏为空（留空提交 = 沿用已保存值），
// 只有新增时才要求必填，否则编辑弹窗永远校验不过
const apiKeyRules = computed(() => (form.value.id ? [] : toRuleFns(rules.apiKey)))
</script>

<style scoped>
.user-picks {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.mapping-input :deep(textarea) {
  font-family: var(--osr-font-mono);
}

.mapping-check {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
}

.mapping-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.mapping-icon {
  margin-top: 2px;
  flex-shrink: 0;
}

.mapping-text {
  min-width: 0;
}

.mapping-source code,
.mapping-libs code {
  margin-left: 4px;
  word-break: break-all;
}

.mapping-verdict {
  word-break: break-all;
}

.mapping-libs {
  opacity: 0.75;
}

.mapping-lib + .mapping-lib::before {
  content: '、';
}
</style>
