import { describe, it, expect } from 'vitest'
import type { SysConfig } from '@/types/system'
import { CONFIG_META, CONFIG_TABS, SECTION_RULES, hintOf, matchesQuery, metaOf, sectionKeyOf } from '../configMeta'
import configItemSource from '../ConfigItem.vue?raw'

const cfg = (configKey: string, extra: Partial<SysConfig> = {}): SysConfig => ({
  configId: 1,
  configName: '',
  configKey,
  configValue: '',
  configType: 'N',
  createTime: '',
  updateTime: '',
  ...extra
})

describe('参数设置 · 配置目录', () => {
  it('每个分组都挂在一个存在的标签上，否则那批配置在页面上整个不出现', () => {
    const tabKeys = new Set(CONFIG_TABS.map(t => t.key))
    for (const rule of SECTION_RULES) {
      expect(tabKeys.has(rule.tab), `${rule.key} → ${rule.tab}`).toBe(true)
    }
  })

  it('说明文字：元数据优先，缺失时退回数据库 remark', () => {
    expect(hintOf(cfg('openlist.tmdb.apikey', { remark: '库里的说明' }))).toBe('TMDb API Key')
    expect(hintOf(cfg('openlist.notify.bark.url', { remark: 'Bark 推送地址' }))).toBe('Bark 推送地址')
    expect(hintOf(cfg('unknown.key'))).toBe('')
  })

  it('登录锁定三项是数字、允许填 0（0 表示关闭）', () => {
    for (const key of ['sys.login.maxRetryCount', 'sys.login.ipMaxRetryCount', 'sys.login.lockMinutes']) {
      const meta = metaOf(cfg(key))
      expect(meta.type).toBe('number')
      expect(meta.min).toBe(0)
    }
    expect(sectionKeyOf('sys.login.lockMinutes')).toBe('security')
  })

  it('键名含 token 的配置编辑时必须是密码框，不能展示时打码、编辑时明文', () => {
    for (const key of Object.keys(CONFIG_META)) {
      if (/token|apikey|secret|aeskey/i.test(key)) {
        expect(CONFIG_META[key].type, key).toBe('password')
      }
    }
  })

  it('已被迁移删除的 notify.*.types 不再留在目录里', () => {
    expect(Object.keys(CONFIG_META).filter(k => k.endsWith('.types'))).toEqual([])
  })

  it('搜索匹配名称、键名、说明，不区分大小写', () => {
    const c = cfg('openlist.tmdb.apikey', { configName: 'TMDb 密钥' })
    expect(matchesQuery(c, 'tmdb')).toBe(true)
    expect(matchesQuery(c, '密钥')).toBe(true)
    expect(matchesQuery(c, 'API KEY')).toBe(true)
    expect(matchesQuery(c, 'openai')).toBe(false)
    expect(matchesQuery(c, '   ')).toBe(true)
  })

  it('下拉用 v-select 而不是 v-combobox（后者默认 returnObject，会把 {label,value} 对象存进配置值）', () => {
    expect(configItemSource).not.toMatch(/<v-combobox/)
  })
})
