import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

/** MCP 令牌的权限档。三档是递进关系，不是独立开关 */
export type McpScope = 'read' | 'write' | 'admin'

export interface McpToken {
  id: number
  name: string
  /** 明文的前若干位，仅供核对是哪一把；完整明文只在签发那一刻出现过一次 */
  tokenPrefix: string
  ownerUserId: number
  scope: McpScope
  enabled: string
  expireTime?: string | null
  lastUsedTime?: string | null
  remark?: string | null
  createTime?: string
}

export interface McpTokenQuery extends SearchParams {
  name?: string
}

export interface McpTokenIssueReq {
  name: string
  scope: McpScope
  /** 有效天数；为空或 <= 0 表示长期有效 */
  expireDays?: number | null
  remark?: string
}

export interface McpTokenIssued {
  record: McpToken
  /**
   * 明文令牌。
   * <b>后端只在这一次响应里给它</b>，之后任何接口都取不回来——页面必须让用户当场复制保存，
   * 关掉弹窗就只能重新签发一枚。
   */
  token: string
}

export function getMcpTokenListApi(params: McpTokenQuery) {
  return request.get<any, PageResult<McpToken>>('/openliststrm/mcp-tokens', { params })
}

export function issueMcpTokenApi(data: McpTokenIssueReq) {
  return request.post<any, McpTokenIssued>('/openliststrm/mcp-tokens', data)
}

export function setMcpTokenEnabledApi(id: number, enabled: boolean) {
  return request.post(`/openliststrm/mcp-tokens/${id}/enabled`, null, { params: { enabled } })
}

export function deleteMcpTokenApi(id: number) {
  return request.delete(`/openliststrm/mcp-tokens/${id}`)
}
