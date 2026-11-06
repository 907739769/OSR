import { ref } from 'vue'
export const setupGuideOpen = ref(false)
export const openSetupGuide = () => { setupGuideOpen.value = true }
