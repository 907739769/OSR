<template>
  <div class="page-container">
    <PageHeader
      icon="bell-ring"
      title="通知路由"
      desc="配置每种通知发送到哪些渠道、发给谁。渠道本身的地址与密钥在「参数设置」里配"
    >
      <template #actions>
        <span v-if="isDirty" class="dirty-hint">{{ dirtyCount }} 处未保存</span>
        <v-btn variant="outlined" prepend-icon="refresh-cw" :disabled="loading" @click="reload">重新加载</v-btn>
        <v-btn
          color="primary"
          variant="flat"
          prepend-icon="save"
          :loading="saving"
          :disabled="!isDirty"
          @click="save"
        >
          保存
        </v-btn>
      </template>
    </PageHeader>

    <v-alert
      v-if="unconfiguredChannels.length"
      type="info"
      variant="tonal"
      density="comfortable"
      class="notice"
    >
      <div class="notice-body">
        <span>
          以下渠道尚未配置，即使在这里开启也不会发送：{{ unconfiguredChannels.map(c => c.name).join('、') }}
          <template v-if="canHideUnconfigured && !showUnconfigured">（已收起）</template>
        </span>
        <span class="notice-actions">
          <v-btn
            v-if="canHideUnconfigured"
            variant="text"
            size="small"
            @click="showUnconfigured = !showUnconfigured"
          >
            {{ showUnconfigured ? '收起未配置的渠道' : '显示未配置的渠道' }}
          </v-btn>
          <v-btn v-if="configPath" variant="text" size="small" append-icon="arrow-right" @click="goConfig">
            去参数设置
          </v-btn>
        </span>
      </div>
    </v-alert>

    <v-card class="table-card">
      <v-progress-linear v-if="refreshing" indeterminate color="primary" />

      <!-- 首屏骨架：渠道列表也是接口给的，加载前矩阵连表头都没有，先按「类型列 + 若干渠道列」画出形状 -->
      <SkeletonTable v-if="firstLoading" :headers="MATRIX_SKELETON" :rows="7" />
      <div v-else class="matrix-scroll">
        <table class="matrix">
          <thead>
            <tr>
              <th class="col-type">通知类型</th>
              <th
                v-for="c in visibleChannels"
                :key="c.key"
                class="col-channel"
                :class="{ 'col-channel--off': !c.configured }"
              >
                <div class="channel-head">
                  <span class="channel-name">
                    <v-icon :icon="channelIcon(c.key)" size="16" />
                    {{ c.name }}
                    <v-icon v-if="!c.configured" icon="circle-alert" size="14" color="warning" />
                  </span>
                  <span class="channel-hint">
                    {{ c.configured ? (c.supportsDirectDelivery ? '可按人投递' : '单一接收人') : '未配置' }}
                  </span>
                  <div class="channel-tools">
                    <v-checkbox-btn
                      :model-value="channelState(c.key) === 'all'"
                      :indeterminate="channelState(c.key) === 'some'"
                      density="compact"
                      label="整列"
                      class="toggle-all"
                      @update:model-value="toggleChannel(c.key)"
                    />
                    <v-btn
                      variant="text"
                      size="x-small"
                      prepend-icon="send"
                      :loading="testingChannel === c.key"
                      :disabled="!c.configured || (!!testingChannel && testingChannel !== c.key)"
                      @click="testChannel(c.key)"
                    >
                      测试
                    </v-btn>
                  </div>
                  <span
                    v-if="testResults[c.key]"
                    class="test-result"
                    :class="testResults[c.key].ok ? 'test-result--ok' : 'test-result--fail'"
                    :title="testResults[c.key].text"
                  >
                    {{ testResults[c.key].ok ? '✓' : '✗' }} {{ testResults[c.key].text }}
                  </span>
                </div>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in types" :key="t.code">
              <td class="col-type">
                <div class="type-head">
                  <v-checkbox-btn
                    :model-value="typeState(t.code) === 'all'"
                    :indeterminate="typeState(t.code) === 'some'"
                    density="compact"
                    class="toggle-all"
                    :aria-label="`${t.label}：整行开关`"
                    @update:model-value="toggleType(t.code)"
                  />
                  <span class="type-name">{{ t.label }}</span>
                  <v-tooltip v-if="t.urgent" location="top" text="Bark 以「时效性通知」响铃、Gotify 以高优先级弹窗；其余渠道无区别">
                    <template #activator="{ props: tip }">
                      <v-chip v-bind="tip" size="x-small" color="warning" variant="tonal" label>高优先级</v-chip>
                    </template>
                  </v-tooltip>
                </div>
                <div class="type-desc">{{ t.description }}</div>
              </td>
              <td
                v-for="c in visibleChannels"
                :key="c.key"
                class="cell"
                :class="{ 'cell--dirty': isCellDirty(t.code, c.key), 'col-channel--off': !c.configured }"
              >
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
        <p>
          「测试」不受上面的开关影响，直接向该渠道的默认接收人发一条测试消息。
        </p>
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import SkeletonTable from '@/components/skeleton/SkeletonTable.vue'
import { useFirstLoad } from '@/composables/useFirstLoad'
import { useNotifyRoute, RECIPIENT_SCOPES, channelIcon } from '@/composables/useNotifyRoute'

const {
  loading, saving, types, cellOf, reload, save,
  dirtyCount, isDirty, isCellDirty,
  unconfiguredChannels, showUnconfigured, canHideUnconfigured, visibleChannels,
  channelState, typeState, toggleChannel, toggleType,
  testingChannel, testResults, testChannel,
  configPath, goConfig
} = useNotifyRoute()
const { firstLoading, refreshing } = useFirstLoad(loading)

/** 矩阵骨架的列：第一列是「类型名 + 说明」两行，其余是渠道格 */
const MATRIX_SKELETON = [
  { key: 'type', minWidth: 260 },
  ...['c1', 'c2', 'c3', 'c4', 'c5'].map((key) => ({ key, align: 'center' as const }))
]
</script>

<style scoped>
.notice {
  margin-bottom: 12px;
}

.notice-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 4px 12px;
}

.notice-actions {
  display: inline-flex;
  gap: 4px;
}

.dirty-hint {
  align-self: center;
  font-size: var(--osr-fs-sm);
  color: var(--osr-warning);
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
  font-size: var(--osr-fs-base);
  font-weight: 600;
  white-space: nowrap;
}

.col-type {
  min-width: 220px;
  max-width: 300px;
}

.col-channel {
  min-width: 160px;
}

/* 未配置的渠道展开时淡化：开关照样能改（先配好路由再去填密钥是正常顺序），但一眼看得出它现在不发 */
.col-channel--off {
  opacity: 0.55;
}

.channel-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.channel-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--osr-text-primary);
}

.channel-hint {
  font-size: var(--osr-fs-xs);
  font-weight: 400;
  color: var(--osr-text-secondary);
}

.channel-tools {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: -8px;
}

.toggle-all {
  flex: none;
  font-weight: 400;
}

.test-result {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: var(--osr-fs-xs);
  font-weight: 400;
  white-space: nowrap;
}

.test-result--ok {
  color: var(--osr-success);
}

.test-result--fail {
  color: var(--osr-error);
}

.type-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: -8px;
}

.type-name {
  font-size: var(--osr-fs-md);
  color: var(--osr-text-primary);
}

.type-desc {
  margin-top: 2px;
  font-size: var(--osr-fs-xs);
  line-height: 1.5;
  color: var(--osr-text-secondary);
}

.cell {
  min-width: 160px;
  transition: background-color var(--osr-transition-fast);
}

/* 改过但还没保存的格子：与「N 处未保存」对上号，用户知道那几处在哪 */
.cell--dirty {
  background: var(--osr-primary-subtle);
}

.cell-switch {
  margin-bottom: 4px;
}

.cell-scope {
  max-width: 130px;
}

.matrix-footer {
  padding: 12px 16px;
  font-size: var(--osr-fs-sm);
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
