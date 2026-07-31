<template>
  <v-menu v-model="dropdownVisible" :close-on-content-click="false" max-height="400">
    <template #activator="{ props: menuProps }">
      <v-text-field
        v-bind="menuProps"
        :model-value="modelValue"
        :placeholder="placeholder"
        readonly
        density="comfortable"
        append-inner-icon="mdi-folder-open-outline"
      />
    </template>
    <v-card min-width="300">
      <v-list density="compact">
        <div v-if="loadingRoot" class="pa-4 text-caption text-medium-emphasis">加载中...</div>
        <TreeNodeItem
          v-for="node in rootNodes"
          :key="node.id"
          :node="node"
          :load-children="loadChildren"
          :active-id="modelValue"
          @select="handleNodeSelect"
        />
      </v-list>
    </v-card>
  </v-menu>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import request from '@/api/request'
import TreeNodeItem from '@/components/TreeNodeItem.vue'

interface DirNode {
  id: string | number
  name: string
  isParent?: boolean
}

const props = withDefaults(defineProps<{
  modelValue?: string
  type?: 'openlist' | 'local'
  placeholder?: string
}>(), {
  modelValue: '',
  type: 'openlist',
  placeholder: '请选择目录'
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const dropdownVisible = ref(false)
const rootNodes = ref<DirNode[]>([])
const loadingRoot = ref(false)

async function fetchDir(id?: string | number): Promise<DirNode[]> {
  const url = props.type === 'openlist' ? '/openliststrm/path/openlist' : '/openliststrm/path/local'
  try {
    const res: any[] = await request.get(id !== undefined ? `${url}?id=${encodeURIComponent(String(id))}` : url)
    return res as DirNode[]
  } catch (e) {
    console.error('Failed to load directory:', e)
    return []
  }
}

async function loadChildren(node: DirNode): Promise<DirNode[]> {
  return fetchDir(node.id)
}

async function loadRoot() {
  loadingRoot.value = true
  try {
    rootNodes.value = await fetchDir()
  } finally {
    loadingRoot.value = false
  }
}

function handleNodeSelect(node: DirNode) {
  emit('update:modelValue', String(node.id))
  dropdownVisible.value = false
}

watch(dropdownVisible, (visible) => {
  if (visible && rootNodes.value.length === 0) {
    loadRoot()
  }
})
</script>
