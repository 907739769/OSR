import type { SysConfig } from '@/types/system'

/**
 * 参数设置页的配置目录：控件类型、说明文案、可选项、分组规则、隐藏项。
 *
 * 从 index.vue 里搬出来的——它是一张会随功能增长而变长的**数据表**（41 个配置起步），
 * 与页面的交互逻辑没有关系，混在一起会让那个文件越来越难找到真正的逻辑。
 */

export type ConfigInputType = 'switch' | 'number' | 'select' | 'text' | 'password' | 'textarea'

export interface ConfigMeta {
  type: ConfigInputType
  hint?: string
  unit?: string
  min?: number
  max?: number
  options?: { label: string; value: string }[]
}

/* ============================================================
   配置项元数据：按 configKey 定义控件类型、说明、可选项等。
   未在此声明的配置键回退为普通文本输入。
   ============================================================ */

const tmdbImageLangOptions = [
  { label: '中文 (zh)', value: 'zh' },
  { label: '英语 (en)', value: 'en' },
  { label: '日语 (ja)', value: 'ja' },
  { label: '韩语 (ko)', value: 'ko' }
]

const tmdbMetaLangOptions = [
  { label: '简体中文 (zh-CN)', value: 'zh-CN' },
  { label: '繁体中文 (zh-TW)', value: 'zh-TW' },
  { label: '英语 (en-US)', value: 'en-US' },
  { label: '日语 (ja-JP)', value: 'ja-JP' },
  { label: '韩语 (ko-KR)', value: 'ko-KR' }
]

const tmdbImageSizeOptions = [
  { label: '原图 (original)', value: 'original' },
  { label: 'w780', value: 'w780' },
  { label: 'w500', value: 'w500' },
  { label: 'w342', value: 'w342' },
  { label: 'w300', value: 'w300' },
  { label: 'w185', value: 'w185' }
]

export const CONFIG_META: Record<string, ConfigMeta> = {
  // Openlist 基础
  'openlist.server.url': { type: 'text', hint: 'OpenList 服务访问地址，例如 http://192.168.1.10:5244' },
  'openlist.server.token': { type: 'password', hint: 'OpenList 管理 API Token' },
  'openlist.api.apikey': { type: 'password', hint: '第三方开放回调接口的鉴权 Key' },
  'openlist.api.refresh': { type: 'switch', hint: '源目录同步列举时是否强制刷新网盘（建议开启以保证增量正确）' },
  'openlist.api.traversal.refresh': { type: 'switch', hint: '目录遍历目标目录时是否强制刷新网盘（关闭走缓存更快，默认关闭）' },
  'openlist.api.traversal.concurrency': { type: 'number', min: 1, max: 64, hint: '目录遍历并发线程数，范围 1-64，默认 10' },
  'openlist.local.allowedroots': { type: 'textarea', hint: '本地目录浏览白名单，多个用英文逗号分隔，默认仅 /data' },
  // 复制 & STRM
  'openlist.copy.minfilesize': { type: 'number', min: 0, unit: 'MB', hint: '小于该大小的文件不会被复制' },
  'openlist.copy.strm': { type: 'switch', hint: '复制完成后是否自动生成 STRM 文件' },
  'openlist.copy.monitor.maxminutes': { type: 'number', min: 1, unit: '分钟', hint: '复制任务监控最长时长，超时未结束将标记为异常，默认 600' },
  'openlist.strm.outputdir': { type: 'text', hint: 'STRM 文件生成的根目录，默认 /data/strm' },
  'openlist.strm.encode': { type: 'switch', hint: 'STRM 内路径是否进行 URL 编码' },
  'openlist.strm.downloadsub': { type: 'switch', hint: '生成 STRM 时是否同时下载字幕文件' },
  'openlist.strm.video.extensions': { type: 'textarea', hint: '判定视频文件的扩展名，逗号分隔、不带点。留空会导致所有文件都不被识别为视频，同步与 STRM 生成将不处理任何文件' },
  'openlist.strm.subtitle.extensions': { type: 'textarea', hint: '判定字幕文件的扩展名，逗号分隔、不带点。仅在开启「生成 STRM 时下载字幕」后生效' },
  // Telegram
  'openlist.tg.token': { type: 'password', hint: 'Telegram 机器人 Token' },
  'openlist.tg.userid': { type: 'text', hint: '允许控制机器人的 Telegram 用户 ID' },
  // 企业微信自建应用
  'openlist.wecom.corpid': { type: 'text', hint: '企业 ID(corpid)，企微管理后台「我的企业」页面查看。留空则企微功能整体不启用' },
  'openlist.wecom.agentid': { type: 'text', hint: '自建应用的 AgentId，「应用管理」→ 自建应用详情页查看' },
  'openlist.wecom.secret': { type: 'password', hint: '自建应用的 Secret，与 corpid 一起换取 access_token' },
  'openlist.wecom.token': { type: 'password', hint: '应用「接收消息」配置里的 Token。不配只能发通知、收不到指令' },
  'openlist.wecom.aeskey': { type: 'password', hint: '应用「接收消息」配置里的 EncodingAESKey（43 位）' },
  'openlist.wecom.touser': { type: 'text', hint: '无归属通知的接收人，多个用 | 分隔，@all 表示应用可见范围内全部成员' },
  'openlist.wecom.autocreate': { type: 'switch', hint: '开启后企微成员首次发指令即自动建 OSR 账号并绑定（账号为停用状态，无法登录网页端），管理员无需逐个建号；关闭则必须先在「企业微信用户」页面建好绑定' },
  'openlist.wecom.proxy': { type: 'text', hint: '企微 API 中转地址。仅 2022-06-20 之后创建的自建应用、且服务器无固定公网 IP 时需要（企微要求登记可信 IP），填反代 qyapi.weixin.qq.com 的地址。不使用代理请保留默认值 https://qyapi.weixin.qq.com' },
  'openlist.notify.wecom.types': { type: 'text', hint: '逗号分隔的通知类型，留空=全部发送。可选：GENERAL,SUBSCRIPTION_HIT,DOWNLOAD_COMPLETE,DOWNLOAD_FAILED,EMBY_LIBRARY_SYNC' },
  // OpenAI
  'openlist.openai.apikey': { type: 'password', hint: 'OpenAI API Key' },
  'openlist.openai.endpoint': { type: 'text', hint: 'OpenAI 接口地址，默认 https://api.openai.com' },
  'openlist.openai.model': { type: 'text', hint: 'OpenAI 模型名称，例如 gpt-5-mini' },
  // TMDb
  'openlist.tmdb.apikey': { type: 'password', hint: 'TMDb API Key' },
  'openlist.tmdb.image.language': { type: 'select', options: tmdbImageLangOptions, hint: 'TMDb 图片语言偏好' },
  'openlist.tmdb.metadata.language': { type: 'select', options: tmdbMetaLangOptions, hint: 'TMDb 元数据（标题/简介）请求语言' },
  'openlist.tmdb.image.size': { type: 'select', options: tmdbImageSizeOptions, hint: 'TMDb 图片下载尺寸，越小越省带宽' }
}

export const metaOf = (config: SysConfig): ConfigMeta => {
  return CONFIG_META[config.configKey] || { type: 'text' }
}

/**
 * 配置分组：按<b>配置键前缀</b>归类，而不是按键名/配置名里的关键词猜。
 *
 * 旧实现是一串 if-else 匹配子串（含 'tg' 就归 Telegram、含 'strm' 就归复制…），
 * 最后一条兜底进「基础配置」。问题是新增一类配置必须回来改这个 if-else，漏改不报错、
 * 也不告警，只是静静地掉进兜底分组——实测 41 个配置里有 15 个掉在那儿，
 * 通知类（webhook/bark/gotify）和登录安全类全在其中。
 *
 * 前缀是这个项目本来就在维护的约定（openlist.notify.* / openlist.wecom.* / sys.login.*），
 * 让键自己声明归属，新增同前缀的配置零改动就能落到正确分组；
 * 全新前缀落进「其他」，看得见但不会错放。
 *
 * 匹配取最长前缀：openlist.notify.tg.types 归「通知渠道」而不是「Telegram 机器人」，
 * 它描述的是通知路由而不是机器人本身。
 */
export const SECTION_RULES: Array<{ key: string; title: string; icon: string; prefixes: string[] }> = [
  { key: 'openlist', title: 'OpenList 服务', icon: 'server',
    prefixes: ['openlist.server.', 'openlist.api.', 'openlist.local.'] },
  { key: 'copy', title: '复制 & STRM 任务', icon: 'arrow-left-right',
    prefixes: ['openlist.copy.', 'openlist.strm.'] },
  { key: 'notify', title: '通知渠道', icon: 'bell',
    prefixes: ['openlist.notify.'] },
  { key: 'tg', title: 'Telegram 机器人', icon: 'brand-telegram',
    prefixes: ['openlist.tg.'] },
  { key: 'wecom', title: '企业微信', icon: 'brand-wecom',
    prefixes: ['openlist.wecom.'] },
  { key: 'tmdb', title: 'TMDb 影视配置', icon: 'zap',
    prefixes: ['openlist.tmdb.'] },
  { key: 'openai', title: 'OpenAI 配置', icon: 'bot',
    prefixes: ['openlist.openai.'] },
  { key: 'security', title: '登录与安全', icon: 'shield-check',
    prefixes: ['sys.login.', 'sys.account.'] },
  { key: 'other', title: '其他', icon: 'ellipsis', prefixes: [] }
]

export const HIDDEN_KEYS = new Set([
  // 重命名文件名模板 → /openlist/renameConfig
  'rename.filename.template'
])

/** 取最长匹配前缀所属的分组，都不匹配归「其他」 */
export const sectionKeyOf = (configKey: string): string => {
  let best = 'other'
  let bestLen = -1
  for (const rule of SECTION_RULES) {
    for (const prefix of rule.prefixes) {
      if (configKey.startsWith(prefix) && prefix.length > bestLen) {
        best = rule.key
        bestLen = prefix.length
      }
    }
  }
  return best
}

const sensitiveKeys = ['token', 'apikey', 'api_key', 'secret', 'password', 'passwd']

export const isSensitive = (key: string): boolean => {
  if (!key) return false
  const lower = key.toLowerCase()
  return sensitiveKeys.some(s => lower.includes(s))
}
