import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import router from '@/router'

const asyncRoutes: RouteRecordRaw[] = []

export const usePermissionStore = defineStore('permission', () => {
  const routes = ref<RouteRecordRaw[]>([])
  const addRoutes = ref<RouteRecordRaw[]>([])

  const generateRoutes = (roles: string[]) => {
    const accessedRoutes = roles.includes('admin') ? [...asyncRoutes] : []
    routes.value = [...(router.options.routes || []), ...accessedRoutes]
    addRoutes.value = accessedRoutes
    return accessedRoutes
  }

  const hasPermission = (_permission: string): boolean => {
    return true
  }

  return { routes, addRoutes, generateRoutes, hasPermission }
})
