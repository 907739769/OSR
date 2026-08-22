<template>
  <div class="page-container">
    <PageHeader
      icon="bell-ring"
      title="通知路由"
      desc="配置每种通知发送到哪些渠道、发给谁。渠道本身的地址与密钥在「参数设置」里配"
    >
      <template #actions>
        <v-btn variant="outlined" prepend-icon="refresh-cw" :disabled="loading" @click="load">重新加载</v-btn>
        <v-btn color="primary" prepend-icon="save" :loading="saving" @click="save">保存</v-btn>
      </template>
    </PageHeader>

    <v-alert
      v-if="unconfiguredChannels.length"
      type="info"
      variant="tonal"
      density="comfortable"
      class="notice"
    >
      以下渠道尚未配置，即使在这里开启也不会发送：{{ unconfiguredChannels.map(c => c.name).join('、') }}
    </v-alert>

    <v-card class="table-card">
      <v-progress-linear v-if="loading" indeterminate color="primary" />

      <div class="matrix-scroll">
        <table class="matrix">
          <thead>
            <tr>
              <th class="col-type">通知类型</th>
              <th v-for="c in channels" :key="c.key" class="col-channel">
                <div class="channel-head">
                  <span class="channel-name">
                    {{ c.name }}
                    <v-icon v-if="!c.configured" icon="circle-alert" size="14" color="warning" />
                  </span>
                  <span v-if="!c.supportsDirectDelivery" class="channel-hint">单一接收人</span>
                  <div class="channel-toggle">
                    <v-btn variant="text" size="x-small" @click="toggleChannel(c.key, true)">全开</v-btn>
                    <v-btn variant="text" size="x-small" @click="toggleChannel(c.key, false)">全关</v-btn>
                  </div>
                </div>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in types" :key="t.code">
              <td class="col-type">
                <div class="type-name">{{ t.label }}</div>
                <code class="type-code">{{ t.code }}</code>
                <div class="type-toggle">
                  <v-btn variant="text" size="x-small" @click="toggleType(t.code, true)">全开</v-btn>
                  <v-btn variant="text" size="x-small" @click="toggleType(t.code, false)">全关</v-btn>
                </div>
              </td>
              <td v-for="c in channels" :key="c.key" class="cell">
                <template v-if="cellOf(t.code, c.key)">
                  <v-switch
                    v-model="cellOf(t.code, c.key)!.enabled"
                    color="primary"
                    density="compact"
                    hide-details
                    class="cell-switch"
                  />
                  <v-select
                    v-if="c.supportsDirectDelivery"
                    v-model="cellOf(t.code, c.key)!.recipientScope"
                    :items="RECIPIENT_SCOPES"
                    :disabled="!cellOf(t.code, c.key)!.enabled"
                    density="compact"
                    variant="outlined"
                    hide-details
                    class="cell-scope"
                  />
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="matrix-footer">
        <p>
          「仅订阅人」在通知没有归属人时（系统告警、历史订阅）会回退给该渠道的默认接收人，不会丢失。
        </p>
        <p>
          标注「单一接收人」的渠道只有一个全局收件地址，无法按人投递，因此不提供收件人选项。
        </p>
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import { useNotifyRoute, RECIPIENT_SCOPES } from '@/composables/useNotifyRoute'

const {
  loading, saving, types, channels,
  cellOf, load, save, toggleChannel, toggleType, unconfiguredChannels
} = useNotifyRoute()
</script>

<style scoped>
.notice {
  margin-bottom: 12px;
}

/* 渠道多起来后横向放不下，让表格自己滚动而不是把整页撑出横向滚动条 */
.matrix-scroll {
  overflow-x: auto;
}

.matrix {
  width: 100%;
  border-collapse: collapse;
}

.matrix th,
.matrix td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--osr-border-light);
  text-align: left;
  vertical-align: top;
}

.matrix thead th {
  background: var(--osr-bg-page);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.col-type {
  min-width: 150px;
}

.col-channel {
  min-width: 150px;
}

.channel-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.channel-name {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--osr-text-primary);
}

.channel-hint {
  font-size: 11px;
  font-weight: 400;
  color: var(--osr-text-secondary);
}

.channel-toggle,
.type-toggle {
  display: flex;
  gap: 2px;
  margin-left: -8px;
}

.type-name {
  font-size: 14px;
  color: var(--osr-text-primary);
}

.type-code {
  font-size: 11px;
  color: var(--osr-text-secondary);
}

.cell {
  min-width: 150px;
}

.cell-switch {
  margin-bottom: 4px;
}

.cell-scope {
  max-width: 130px;
}

.matrix-footer {
  padding: 12px 16px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--osr-text-secondary);

  p {
    margin: 0;
  }
}

@media (max-width: 768px) {
  .page-container {
    padding: 0;
  }
}
</style>
