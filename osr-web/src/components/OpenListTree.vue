<template>
  <div class="openlist-tree">
    <v-text-field
      v-model="filterText"
      placeholder="搜索目录..."
      prepend-inner-icon="search"
      clearable
      density="compact"
      class="tree-search"
    />
    <v-list density="compact">
      <template v-for="node in filteredTreeData" :key="node.id">
        <v-list-item @click="handleNodeClick(node)">
          <template #prepend>
            <v-icon
              :icon="node.type === 'folder' ? 'folder' : 'file'"
              :color="node.type === 'folder' ? 'warning' : 'grey'"
              size="18"
            />
          </template>
          <v-list-item-title>{{ node.label }}</v-list-item-title>
          <template v-if="node.size" #append>
            <span class="tree-size">{{ formatSize(node.size) }}</span>
          </template>
        </v-list-item>
      </template>
    </v-list>
    <div class="tree-footer" v-if="selectedPath">
      <v-chip size="small" color="info" variant="tonal">{{ selectedPath }}</v-chip>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getOpenlistPathApi } from '@/api/openlist/path'

interface TreeNode {
  id: string | number
  label: string
  type: 'folder' | 'file'
  path?: string
  size?: number
  children?: TreeNode[]
}

const props = withDefaults(defineProps<{
  mode?: 'openlist' | 'local'
  multiple?: boolean
  defaultExpandAll?: boolean
}>(), {
  mode: 'openlist',
  multiple: false,
  defaultExpandAll: false
})

const emit = defineEmits<{
  select: [node: TreeNode]
}>()

const filterText = ref('')
const treeData = ref<TreeNode[]>([])
const selectedPath = ref('')

const filteredTreeData = computed(() => {
  if (!filterText.value) return treeData.value
  return treeData.value.filter((n) => n.label?.includes(filterText.value))
})

const loadTree = async (parentId?: number) => {
  try {
    const params: any = { parentId }
    if (props.mode === 'local') {
      const { getLocalPathApi } = await import('@/api/openlist/path')
      const res = await getLocalPathApi(params) as TreeNode[]
      treeData.value = res
    } else {
      const res = await getOpenlistPathApi(params) as TreeNode[]
      treeData.value = res
    }
  } catch (e) {
    console.error(e)
  }
}

const handleNodeClick = (data: TreeNode) => {
  if (data.type === 'folder') {
    loadTree(typeof data.id === 'number' ? data.id : undefined)
  } else {
    selectedPath.value = data.path || ''
    emit('select', data)
  }
}

const formatSize = (bytes: number): string => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(2) + ' ' + sizes[i]
}

onMounted(() => {
  loadTree()
})

defineExpose({ loadTree })
</script>

<style scoped lang="scss">
.openlist-tree {
  .tree-search {
    margin-bottom: 12px;
  }

  .tree-size {
    margin-left: auto;
    font-size: 12px;
    color: var(--osr-text-secondary);
  }

  .tree-footer {
    padding: 8px 0;
  }
}
</style>
