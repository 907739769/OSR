-- ----------------------------
-- 20260718: 补全后端已读取但数据库未初始化的 openlist.* 参数配置
-- 这些参数此前仅有代码默认值，参数设置页看不到、也无法修改。
-- 采用 INSERT ... WHERE NOT EXISTS 保证幂等，已存在的键不会被覆盖。
-- ----------------------------

-- STRM 输出目录
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'STRM输出目录', 'openlist.strm.outputdir', '/data/strm', 'N', 'admin', '2026-07-18 00:00:00', 'STRM 文件生成的根目录，默认 /data/strm'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.strm.outputdir');

-- STRM 路径编码开关
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'STRM路径编码', 'openlist.strm.encode', '0', 'N', 'admin', '2026-07-18 00:00:00', 'STRM 内路径是否进行 URL 编码 0-否 1-是'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.strm.encode');

-- STRM 下载字幕开关
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'STRM下载字幕', 'openlist.strm.downloadsub', '0', 'N', 'admin', '2026-07-18 00:00:00', '生成 STRM 时是否同时下载字幕文件 0-否 1-是'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.strm.downloadsub');

-- 源目录同步是否强制刷新网盘
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '同步强制刷新网盘', 'openlist.api.refresh', '1', 'N', 'admin', '2026-07-18 00:00:00', '源目录同步列举时是否强制刷新网盘 0-否 1-是（默认开启保证增量正确）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.api.refresh');

-- 目录遍历是否强制刷新网盘
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '遍历强制刷新网盘', 'openlist.api.traversal.refresh', '0', 'N', 'admin', '2026-07-18 00:00:00', 'STRM生成/同步遍历目标目录时是否强制刷新网盘 0-否 1-是（默认关闭走缓存，更快）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.api.traversal.refresh');

-- 目录遍历并发度
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '目录遍历并发度', 'openlist.api.traversal.concurrency', '10', 'N', 'admin', '2026-07-18 00:00:00', '目录遍历的并发线程数，范围 1-64，默认 10'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.api.traversal.concurrency');

-- 复制任务监控最长时长（分钟）
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '复制监控最长时长', 'openlist.copy.monitor.maxminutes', '600', 'N', 'admin', '2026-07-18 00:00:00', '复制任务监控最长持续时间（分钟），超时未结束标记为异常，默认 600'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.copy.monitor.maxminutes');

-- 本地目录浏览白名单
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '本地目录白名单', 'openlist.local.allowedroots', '/data', 'N', 'admin', '2026-07-18 00:00:00', '本地目录浏览接口允许访问的根目录白名单，多个用英文逗号分隔，默认仅 /data'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.local.allowedroots');

-- TMDb 图片语言偏好
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'TMDb图片语言', 'openlist.tmdb.image.language', 'zh', 'N', 'admin', '2026-07-18 00:00:00', 'TMDb 图片语言偏好（ISO 639-1），如 zh/en/ja/ko，默认 zh'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.tmdb.image.language');

-- TMDb 元数据语言
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'TMDb元数据语言', 'openlist.tmdb.metadata.language', 'zh-CN', 'N', 'admin', '2026-07-18 00:00:00', 'TMDb 元数据（标题/简介）请求语言，如 zh-CN/en-US，默认 zh-CN'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.tmdb.metadata.language');

-- TMDb 图片下载尺寸
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'TMDb图片尺寸', 'openlist.tmdb.image.size', 'original', 'N', 'admin', '2026-07-18 00:00:00', 'TMDb 图片下载尺寸，original 或 w780/w500/w342 等以节省带宽，默认 original'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.tmdb.image.size');
