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
          <v-btn variant="text" size="small" prepend-icon="refresh-cw" :disabled="loading" @click="load">重新加载</v-btn>
        </div>
        <div class="action-right">
          <v-btn color="primary" size="small" variant="flat" prepend-icon="save" :loading="saving" @click="save">
            保存
          </v-btn>
        </div>
      </div>

      <v-alert
        v-if="unconfiguredChannels.length"
        type="info"
        variant="tonal"
        density="compact"
        class="notice"
      >
        未配置的渠道即使开启也不会发送：{{ unconfiguredChannels.map(c => c.name).join('、') }}
      </v-alert>

      <v-progress-linear v-if="loading" indeterminate color="primary" />

      <!-- 移动端横向放不下矩阵，改成按通知类型分组：一个类型一张卡，卡里逐渠道列 -->
    </template>

    <v-card v-for="t in types" :key="t.code" class="task-card">
      <div class="card-content">
        <div class="card-top">
          <div class="card-title-row">
            <v-icon class="card-title-icon" icon="bell" size="18" />
            <span class="card-title">{{ t.label }}</span>
          </div>
          <div class="type-toggle">
            <v-btn variant="text" size="x-small" @click="toggleType(t.code, true)">全开</v-btn>
            <v-btn variant="text" size="x-small" @click="toggleType(t.code, false)">全关</v-btn>
          </div>
        </div>

        <div v-for="c in channels" :key="c.key" class="channel-row">
          <div class="channel-label">
            <span>
              {{ c.name }}
              <v-icon v-if="!c.configured" icon="circle-alert" size="13" color="warning" />
            </span>
            <span v-if="!c.supportsDirectDelivery" class="channel-hint">单一接收人</span>
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
      </p>
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import { useNotifyRoute, RECIPIENT_SCOPES } from '@/composables/useNotifyRoute'
import MobileListPage from '@/components/mobile/MobileListPage.vue'

const {
  loading, saving, types, channels,
  cellOf, load, save, toggleType, unconfiguredChannels
} = useNotifyRoute()
</script>

<style scoped>
.notice {
  margin-bottom: 10px;
}

.type-toggle {
  display: flex;
  gap: 2px;
}

.channel-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-top: 1px solid var(--osr-border-light);
}

.channel-label {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  font-size: 13px;
  color: var(--osr-text-primary);
}

.channel-hint {
  font-size: 11px;
  color: var(--osr-text-secondary);
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
  font-size: 12px;
  line-height: 1.7;
  color: var(--osr-text-secondary);
}
</style>
