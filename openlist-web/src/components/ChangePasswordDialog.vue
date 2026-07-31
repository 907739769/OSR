<template>
  <v-dialog v-model="visible" max-width="420" @update:model-value="onDialogUpdate">
    <v-card title="修改密码">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="handleSubmit">
          <v-text-field
            v-model="form.oldPassword"
            label="旧密码"
            type="password"
            :rules="[requiredRule('请输入旧密码')]"
            class="mb-2"
          />
          <v-text-field
            v-model="form.newPassword"
            label="新密码"
            type="password"
            :rules="[requiredRule('请输入新密码'), lengthRule]"
            class="mb-2"
          />
          <v-text-field
            v-model="form.confirmPassword"
            label="确认密码"
            type="password"
            :rules="[requiredRule('请确认新密码'), confirmPasswordRule]"
          />
        </v-form>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="visible = false">取消</v-btn>
        <v-btn color="primary" variant="flat" :loading="loading" @click="handleSubmit">确定</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import type { VForm } from 'vuetify/components'
import { message } from '@/composables/useMessage'
import { changePasswordApi } from '@/api/auth'

const emit = defineEmits(['update:visible'])
const props = defineProps<{ visible: boolean }>()

const formRef = ref<InstanceType<typeof VForm>>()
const loading = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const requiredRule = (msg: string) => (value: string) => !!value || msg
const lengthRule = (value: string) => (value.length >= 6 && value.length <= 20) || '长度在 6 到 20 个字符'
const confirmPasswordRule = (value: string) => value === form.newPassword || '两次输入的密码不一致'

const visible = ref(props.visible)

defineExpose({ visible })

watch(() => props.visible, (val) => {
  visible.value = val
})

function onDialogUpdate(val: boolean) {
  if (!val) handleClose()
}

const handleSubmit = async () => {
  const { valid } = await formRef.value!.validate()
  if (!valid) return
  loading.value = true
  try {
    await changePasswordApi({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    message.success('密码修改成功')
    handleClose()
  } catch (error: any) {
    message.error(error.message || '修改密码失败')
  } finally {
    loading.value = false
  }
}

const handleClose = () => {
  formRef.value?.reset()
  visible.value = false
  emit('update:visible', false)
}
</script>
