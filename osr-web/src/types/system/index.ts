export interface SysUser {
  userId: number
  deptId: number
  loginName: string
  userName: string
  email: string
  phonenumber: string
  sex: string
  status: string
  delFlag: string
  loginIp: string
  loginDate: string
  createTime: string
  updateTime: string
  remark: string
  roles: SysRole[]
  roleIds?: number[]
}

export interface SysRole {
  roleId: number
  roleName: string
  roleKey: string
  roleSort: number
  dataScope: string
  status: string
  delFlag: string
  createTime: string
  updateTime: string
  remark: string
  menuIds?: number[]
  deptIds?: number[]
  sysUser?: SysUser[]
}

export interface SysMenu {
  menuId: number
  menuName: string
  parentId: number
  orderNum: number
  path: string
  component: string
  query: string
  isFrame: number
  isCache: number
  menuType: string
  visible: string
  status: string
  perms: string
  icon: string
  createTime: string
  updateTime: string
  remark: string
  children?: SysMenu[]
}

export interface SysConfig {
  configId: number
  configName: string
  configKey: string
  configValue: string
  configType: string
  createTime: string
  updateTime: string
  remark?: string
}

export interface SysDictType {
  dictId: number
  dictName: string
  dictType: string
  status: string
  createTime: string
  updateTime: string
  remark: string
}

export interface SysDictData {
  dictCode: number
  dictLabel: string
  dictValue: string
  dictType: string
  dictSort: number
  cssClass: string
  listClass: string
  isDefault: string
  status: string
  createTime: string
  updateTime: string
  remark: string
}

