<template>
  <div class="error-page">
    <v-empty-state icon="triangle-alert" title="404" text="页面不存在">
      <template #actions>
        <v-btn color="primary" @click="goHome">返回首页</v-btn>
      </template>
    </v-empty-state>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const goHome = () => {
  router.push('/')
}
</script>

<style scoped>
.error-page {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
}
</style>
