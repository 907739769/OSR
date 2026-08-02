<template>
  <v-list-group v-if="node.isParent" :value="node.id">
    <template #activator="{ props: activatorProps }">
      <v-list-item
        v-bind="activatorProps"
        :active="node.id === activeId"
        density="compact"
        @click="handleExpand"
      >
        <template #prepend>
          <v-icon icon="mdi-folder-outline" color="warning" size="18" />
        </template>
        <v-list-item-title>{{ node.name }}</v-list-item-title>
        <template #append>
          <v-btn
            icon="mdi-check-circle-outline"
            variant="text"
            size="x-small"
            density="compact"
            title="选中此目录"
            @click.stop="emit('select', node)"
          />
        </template>
      </v-list-item>
    </template>
    <div v-if="loading" class="pl-8 py-2 text-caption text-medium-emphasis">加载中...</div>
    <TreeNodeItem
      v-for="child in children"
      :key="child.id"
      :node="child"
      :load-children="loadChildren"
      :active-id="activeId"
      @select="emit('select', $event)"
    />
  </v-list-group>
  <v-list-item
    v-else
    :active="node.id === activeId"
    density="compact"
    @click="emit('select', node)"
  >
    <template #prepend>
      <v-icon icon="mdi-file-outline" color="grey" size="18" />
    </template>
    <v-list-item-title>{{ node.name }}</v-list-item-title>
  </v-list-item>
</template>

<script setup lang="ts">
import { ref } from 'vue'

interface TreeItemNode {
  id: string | number
  name: string
  isParent?: boolean
}

const props = defineProps<{
  node: TreeItemNode
  loadChildren: (node: TreeItemNode) => Promise<TreeItemNode[]>
  activeId?: string | number
}>()

const emit = defineEmits<{ select: [node: TreeItemNode] }>()

const children = ref<TreeItemNode[]>([])
const loading = ref(false)
let loaded = false

async function handleExpand() {
  if (loaded) return
  loading.value = true
  try {
    children.value = await props.loadChildren(props.node)
  } finally {
    loading.value = false
    loaded = true
  }
}
</script>
