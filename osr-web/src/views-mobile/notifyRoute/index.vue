<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && types.length === 0"
    empty-icon="bell-off"
    empty-title="暂无通知类型"
  >
    <template #head>
      <div class="action-bar">
        <div class="action-left">
          <v-btn variant="text" size="small" prepend-icon="refresh-cw" :disabled="loading" @click="reload">重新加载</v-btn>
        </div>
        <span v-if="isDirty" class="dirty-hint">{{ dirtyCount }} 处未保存</span>
      </div>

      <v-alert
        v-if="unconfiguredChannels.length"
        type="info"
        variant="tonal"
        density="compact"
        class="notice"
      >
        未配置的渠道即使开启也不会发送：{{ unconfiguredChannels.map(c => c.name).join('、') }}
        <div class="notice-actions">
          <v-btn
            v-if="canHideUnconfigured"
            variant="text"
            size="x-small"
            @click="showUnconfigured = !showUnconfigured"
          >
            {{ showUnconfigured ? '收起未配置的渠道' : '显示未配置的渠道' }}
          </v-btn>
          <v-btn v-if="configPath" variant="text" size="x-small" append-icon="arrow-right" @click="goConfig">
            去参数设置
          </v-btn>
        </div>
      </v-alert>

      <!-- 渠道总览：矩阵的「列」在移动端没有位置，整列开关与发送测试收在这张卡里 -->
      <v-card v-if="visibleChannels.length" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <div class="card-title-row">
              <v-icon class="card-title-icon" icon="send" size="18" />
              <span class="card-title">渠道</span>
            </div>
          </div>
          <div
            v-for="c in visibleChannels"
            :key="c.key"
            class="channel-row"
            :class="{ 'channel-row--off': !c.configured }"
          >
            <v-checkbox-btn
              :model-value="channelState(c.key) === 'all'"
              :indeterminate="channelState(c.key) === 'some'"
              density="compact"
              class="toggle-all"
              :aria-label="`${c.name}：所有类型开关`"
              @update:model-value="toggleChannel(c.key)"
            />
            <div class="channel-label">
              <span class="channel-name">
                <v-icon :icon="channelIcon(c.key)" size="14" />
                {{ c.name }}
                <v-icon v-if="!c.configured" icon="circle-alert" size="13" color="warning" />
              </span>
              <span
                v-if="testResults[c.key]"
                class="test-result"
                :class="testResults[c.key].ok ? 'test-result--ok' : 'test-result--fail'"
              >
                {{ testResults[c.key].ok ? '✓' : '✗' }} {{ testResults[c.key].text }}
              </span>
              <span v-else class="channel-hint">
                {{ c.configured ? (c.supportsDirectDelivery ? '可按人投递' : '单一接收人') : '未配置' }}
              </span>
            </div>
            <v-btn
              variant="outlined"
              size="small"
              prepend-icon="send"
              :loading="testingChannel === c.key"
              :disabled="!c.configured || (!!testingChannel && testingChannel !== c.key)"
              @click="testChannel(c.key)"
            >
              测试
            </v-btn>
          </div>
        </div>
      </v-card>

      <!-- 移动端横向放不下矩阵，改成按通知类型分组：一个类型一张卡，卡里逐渠道列 -->
    </template>

    <v-card v-for="t in types" :key="t.code" class="task-card">
      <div class="card-content">
        <div class="card-top">
          <div class="card-title-row">
            <v-checkbox-btn
              :model-value="typeState(t.code) === 'all'"
              :indeterminate="typeState(t.code) === 'some'"
              density="compact"
              class="toggle-all"
              :aria-label="`${t.label}：整组开关`"
              @update:model-value="toggleType(t.code)"
            />
            <span class="card-title">{{ t.label }}</span>
            <v-chip v-if="t.urgent" size="x-small" color="warning" variant="tonal" label>高优先级</v-chip>
          </div>
        </div>
        <div class="type-desc">{{ t.description }}</div>

        <div
          v-for="c in visibleChannels"
          :key="c.key"
          class="channel-row"
          :class="{ 'channel-row--dirty': isCellDirty(t.code, c.key), 'channel-row--off': !c.configured }"
        >
          <div class="channel-label">
            <span class="channel-name">
              <v-icon :icon="channelIcon(c.key)" size="14" />
              {{ c.name }}
            </span>
          </div>
          <template v-if="cellOf(t.code, c.key)">
            <v-select
              v-if="c.supportsDirectDelivery"
              v-model="cellOf(t.code, c.key)!.recipientScope"
              :items="RECIPIENT_SCOPES"
              :disabled="!cellOf(t.code, c.key)!.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="channel-scope"
            />
            <v-switch
              v-model="cellOf(t.code, c.key)!.enabled"
              color="primary"
              density="compact"
              hide-details
              class="channel-switch"
            />
          </template>
        </div>
      </div>
    </v-card>

    <template #foot>
      <p class="footer-note">
        「仅订阅人」在通知没有归属人时（系统告警、历史订阅）会回退给该渠道的默认接收人，不会丢失。
        「测试」不受开关影响，直接向该渠道的默认接收人发一条测试消息。
        「高优先级」的通知在 Bark 上会响铃、在 Gotify 上会弹窗。
      </p>
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import { useNotifyRoute, RECIPIENT_SCOPES, channelIcon } from '@/composables/useNotifyRoute'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import { useMobilePageAction } from '@/composables/useMobilePageAction'

const {
  loading, saving, types, cellOf, reload, save,
  dirtyCount, isDirty, isCellDirty,
  unconfiguredChannels, showUnconfigured, canHideUnconfigured, visibleChannels,
  channelState, typeState, toggleChannel, toggleType,
  testingChannel, testResults, testChannel,
  configPath, goConfig
} = useNotifyRoute()

// 保存并在悬浮底栏右侧：这页是「通知类型 × 渠道」的长列表，改完最底下几项后
// 原先要滚回顶部才点得到保存。「重新加载」刻意留在顶部——它会丢掉未保存的修改，
// 不能放在一点就触发的位置（有改动时它还会先确认）
useMobilePageAction(() => ({
  icon: 'save',
  label: isDirty.value ? `保存通知路由（${dirtyCount.value} 处未保存）` : '保存通知路由',
  loading: saving.value,
  onClick: () => save()
}))
</script>

<style scoped>
.notice {
  margin-bottom: 10px;
}

.notice-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: 4px 0 0 -8px;
}

.dirty-hint {
  align-self: center;
  font-size: var(--osr-fs-sm);
  color: var(--osr-warning);
}

.toggle-all {
  flex: none;
}

.type-desc {
  font-size: var(--osr-fs-xs);
  line-height: 1.5;
  color: var(--osr-text-secondary);
}

.channel-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-top: 1px solid var(--osr-border-light);
  transition: background-color var(--osr-transition-fast);
}

.channel-row--dirty {
  background: var(--osr-primary-subtle);
}

.channel-row--off {
  opacity: 0.55;
}

.channel-label {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  font-size: var(--osr-fs-base);
  color: var(--osr-text-primary);
}

.channel-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.channel-hint {
  font-size: var(--osr-fs-xs);
  color: var(--osr-text-secondary);
}

/* 失败原因要读得全（token 错了还是接收人不对），手机上没有 title 悬浮可看，允许折行 */
.test-result {
  font-size: var(--osr-fs-xs);
  word-break: break-all;
}

.test-result--ok {
  color: var(--osr-success);
}

.test-result--fail {
  color: var(--osr-error);
}

.channel-scope {
  flex: none;
  /* 136px 是「仅订阅人」在 compact + outlined 下不被截断的宽度 */
  width: 136px;
}

.channel-switch {
  flex: none;
}

.footer-note {
  margin: 12px 4px 0;
  font-size: var(--osr-fs-sm);
  line-height: 1.7;
  color: var(--osr-text-secondary);
}
</style>
