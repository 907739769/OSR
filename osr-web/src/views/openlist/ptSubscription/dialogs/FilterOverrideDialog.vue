<template>
  <!-- 过滤规则覆盖 -->
  <v-dialog v-model="filterOverrideOpen" max-width="600">
    <v-card :title="filterOverrideCount ? `过滤规则覆盖（已覆盖 ${filterOverrideCount} 项）` : '过滤规则覆盖'">
      <v-card-text>
        <p class="override-tip">
          勾选左侧方框才会覆盖该项，未勾选的沿用「PT 过滤规则」页的全局配置——每行末尾的灰字就是当前的全局取值。
        </p>
        <v-form>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.minSeeders.enabled" class="override-checkbox" />
            <span class="override-label">最低做种数</span>
            <v-text-field
              v-model.number="filterOverrideForm.minSeeders.value"
              type="number"
              min="0"
              :disabled="!filterOverrideForm.minSeeders.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('minSeeders')" class="override-global">{{ globalFilterHint('minSeeders') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.minSize.enabled" class="override-checkbox" />
            <span class="override-label">体积下限</span>
            <v-text-field
              v-model.number="filterOverrideForm.minSize.value"
              type="number"
              min="0"
              step="0.01"
              max="999"
              suffix="GB"
              :disabled="!filterOverrideForm.minSize.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('minSize')" class="override-global">{{ globalFilterHint('minSize') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.maxSize.enabled" class="override-checkbox" />
            <span class="override-label">体积上限</span>
            <v-text-field
              v-model.number="filterOverrideForm.maxSize.value"
              type="number"
              min="0"
              step="0.01"
              max="999"
              suffix="GB"
              :disabled="!filterOverrideForm.maxSize.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('maxSize')" class="override-global">{{ globalFilterHint('maxSize') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.freeOnly.enabled" class="override-checkbox" />
            <span class="override-label">仅要免费种</span>
            <v-radio-group v-model="filterOverrideForm.freeOnly.value" inline hide-details :disabled="!filterOverrideForm.freeOnly.enabled">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <span v-if="globalFilterHint('freeOnly')" class="override-global">{{ globalFilterHint('freeOnly') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.requireChineseSubtitle.enabled" class="override-checkbox" />
            <span class="override-label">外语电影需中字</span>
            <v-radio-group v-model="filterOverrideForm.requireChineseSubtitle.value" inline hide-details :disabled="!filterOverrideForm.requireChineseSubtitle.enabled">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <span v-if="globalFilterHint('requireChineseSubtitle')" class="override-global">{{ globalFilterHint('requireChineseSubtitle') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.resolutionWhitelist.enabled" class="override-checkbox" />
            <span class="override-label">分辨率白名单</span>
            <v-text-field
              v-model="filterOverrideForm.resolutionWhitelist.value"
              placeholder="如 2160p,1080p"
              :disabled="!filterOverrideForm.resolutionWhitelist.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('resolutionWhitelist')" class="override-global">{{ globalFilterHint('resolutionWhitelist') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.includeKeywords.enabled" class="override-checkbox" />
            <span class="override-label">标题包含词</span>
            <v-text-field
              v-model="filterOverrideForm.includeKeywords.value"
              placeholder="逗号分隔，命中其一即可"
              :disabled="!filterOverrideForm.includeKeywords.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('includeKeywords')" class="override-global">{{ globalFilterHint('includeKeywords') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.excludeKeywords.enabled" class="override-checkbox" />
            <span class="override-label">标题排除词</span>
            <v-text-field
              v-model="filterOverrideForm.excludeKeywords.value"
              placeholder="逗号分隔，命中任一即淘汰"
              :disabled="!filterOverrideForm.excludeKeywords.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('excludeKeywords')" class="override-global">{{ globalFilterHint('excludeKeywords') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.descriptionExcludeKeywords.enabled" class="override-checkbox" />
            <span class="override-label">描述排除词</span>
            <v-text-field
              v-model="filterOverrideForm.descriptionExcludeKeywords.value"
              placeholder="如 原盘,BDMV；匹配描述而非标题"
              :disabled="!filterOverrideForm.descriptionExcludeKeywords.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('descriptionExcludeKeywords')" class="override-global">{{ globalFilterHint('descriptionExcludeKeywords') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.resolutionPriority.enabled" class="override-checkbox" />
            <span class="override-label">分辨率优先级</span>
            <v-text-field
              v-model="filterOverrideForm.resolutionPriority.value"
              placeholder="如 2160p,1080p,720p"
              :disabled="!filterOverrideForm.resolutionPriority.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('resolutionPriority')" class="override-global">{{ globalFilterHint('resolutionPriority') }}</span>
          </div>
          <div class="override-row">
            <v-checkbox-btn v-model="filterOverrideForm.preferredSize.enabled" class="override-checkbox" />
            <span class="override-label">偏好体积</span>
            <v-text-field
              v-model.number="filterOverrideForm.preferredSize.value"
              type="number"
              min="0"
              step="0.01"
              max="999"
              suffix="GB"
              :disabled="!filterOverrideForm.preferredSize.enabled"
              density="compact"
              variant="outlined"
              hide-details
              class="override-input"
            />
            <span v-if="globalFilterHint('preferredSize')" class="override-global">{{ globalFilterHint('preferredSize') }}</span>
          </div>
        </v-form>
      </v-card-text>
      <v-card-actions>
        <!-- 「退回全局」是很常见的一次性意图，逐个取消 11 个勾选太啰嗦 -->
        <v-btn variant="text" :disabled="!filterOverrideCount" @click="clearFilterOverride">全部清除</v-btn>
        <v-spacer />
        <v-btn variant="outlined" @click="filterOverrideOpen = false">取消</v-btn>
        <v-btn color="primary" variant="flat" :loading="filterOverrideSaving" @click="saveFilterOverride">保存</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

const {
  clearFilterOverride,
  filterOverrideCount,
  filterOverrideForm,
  filterOverrideOpen,
  filterOverrideSaving,
  globalFilterHint,
  saveFilterOverride
} = usePtSubscriptionContext()
</script>

<style scoped lang="scss">
.override-tip {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}
/* 勾选框在最左：它是这一行的总开关，夹在标签和输入框中间时扫视都找不到它在哪 */
.override-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.override-checkbox {
  flex: none;
}
.override-label {
  width: 110px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--osr-text-secondary);
}
.override-input {
  flex: 1;
  min-width: 150px;
}
/* 全局取值参照：勾上覆盖那一刻用户得知道自己在把多少改成多少 */
.override-global {
  flex-shrink: 0;
  min-width: 108px;
  font-size: 12px;
  color: var(--osr-text-disabled);
}
</style>
