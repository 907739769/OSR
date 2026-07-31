export interface RuleOption {
  value: string
  label: string
}

/** 电影 genre（TMDB movie genre list） */
export const MOVIE_GENRE_OPTIONS: RuleOption[] = [
  { value: '28', label: '动作' },
  { value: '12', label: '冒险' },
  { value: '16', label: '动画' },
  { value: '35', label: '喜剧' },
  { value: '80', label: '犯罪' },
  { value: '99', label: '纪录片' },
  { value: '18', label: '剧情' },
  { value: '10751', label: '家庭' },
  { value: '14', label: '奇幻' },
  { value: '36', label: '历史' },
  { value: '27', label: '恐怖' },
  { value: '10402', label: '音乐' },
  { value: '9648', label: '悬疑' },
  { value: '10749', label: '爱情' },
  { value: '878', label: '科幻' },
  { value: '10770', label: '电视电影' },
  { value: '53', label: '惊悚' },
  { value: '10752', label: '战争' },
  { value: '37', label: '西部' }
]

/** 剧集 genre（TMDB tv genre list，与电影列表编号含义不完全相同） */
export const TV_GENRE_OPTIONS: RuleOption[] = [
  { value: '10759', label: '动作冒险' },
  { value: '16', label: '动画' },
  { value: '35', label: '喜剧' },
  { value: '80', label: '犯罪' },
  { value: '99', label: '纪录片' },
  { value: '18', label: '剧情' },
  { value: '10751', label: '家庭' },
  { value: '10762', label: '儿童' },
  { value: '9648', label: '悬疑' },
  { value: '10763', label: '新闻' },
  { value: '10764', label: '真人秀' },
  { value: '10765', label: '科幻奇幻' },
  { value: '10766', label: '肥皂剧' },
  { value: '10767', label: '脱口秀' },
  { value: '10768', label: '战争政治' },
  { value: '37', label: '西部' }
]

/** 常见原始语言（ISO 639-1，附本项目历史数据里用到的 cn/bo/za 三个非标准/少数民族语言码） */
export const LANGUAGE_OPTIONS: RuleOption[] = [
  { value: 'zh', label: '中文' },
  { value: 'cn', label: '华语' },
  { value: 'bo', label: '藏语' },
  { value: 'za', label: '壮语' },
  { value: 'en', label: '英语' },
  { value: 'ja', label: '日语' },
  { value: 'ko', label: '韩语' },
  { value: 'fr', label: '法语' },
  { value: 'de', label: '德语' },
  { value: 'es', label: '西班牙语' },
  { value: 'it', label: '意大利语' },
  { value: 'ru', label: '俄语' },
  { value: 'pt', label: '葡萄牙语' },
  { value: 'nl', label: '荷兰语' },
  { value: 'th', label: '泰语' },
  { value: 'hi', label: '印地语' }
]

/** 常见国家/地区（ISO 3166-1，附本项目历史数据里用到的非标准码 UK，等价于 GB） */
export const COUNTRY_OPTIONS: RuleOption[] = [
  { value: 'CN', label: '中国大陆' },
  { value: 'TW', label: '中国台湾' },
  { value: 'HK', label: '中国香港' },
  { value: 'JP', label: '日本' },
  { value: 'KR', label: '韩国' },
  { value: 'KP', label: '朝鲜' },
  { value: 'US', label: '美国' },
  { value: 'GB', label: '英国' },
  { value: 'UK', label: '英国(UK)' },
  { value: 'FR', label: '法国' },
  { value: 'DE', label: '德国' },
  { value: 'ES', label: '西班牙' },
  { value: 'IT', label: '意大利' },
  { value: 'NL', label: '荷兰' },
  { value: 'PT', label: '葡萄牙' },
  { value: 'RU', label: '俄罗斯' },
  { value: 'TH', label: '泰国' },
  { value: 'IN', label: '印度' },
  { value: 'SG', label: '新加坡' }
]
