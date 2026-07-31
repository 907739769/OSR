import request from '@/api/request'

export function getOpenlistPathApi(params: { parentId?: number; type?: string }) {
  return request.get<any, any[]>('/openliststrm/path/openlist', { params })
}

export function getLocalPathApi(params: { parentId?: number }) {
  return request.get<any, any[]>('/openliststrm/path/local', { params })
}
