import { getRoutePathForComponent } from '@/router'
export interface WorkflowStep { stage: string; state: string; message: string; component: string | null; recordKey: string | null; recordId: number | null }
export function workflowLocation(step: WorkflowStep, subId?: number) {
  const path = step.component && getRoutePathForComponent(step.component)
  if (!path) return null
  const query: Record<string, string> = {}
  if (step.recordKey && step.recordId != null) query[step.recordKey] = String(step.recordId)
  if (step.component === 'openlist/ptDownloadRecord/index' && subId) query.subId = String(subId)
  return { path, query }
}
