-- ----------------------------
-- 20260772: 同步遍历跳过「Transmission 删种临时目录」的识别规则
-- Transmission 的 tr_torrent_files::remove() 删本地数据时会先建一个 <种子名>__XXXXXX 的
-- mkdtemp 临时目录（六个 X 替换成 [A-Za-z0-9] 随机字符）、把内容整个挪进去再删，
-- 同步任务的目录遍历撞上它就会建出网盘空目录并提交注定失败的复制任务。qBittorrent 无此行为。
-- 采用 INSERT ... WHERE NOT EXISTS 保证幂等，已存在的键不会被覆盖。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '同步跳过的临时目录规则', 'openlist.copy.transientdirs', '.+__[0-9A-Za-z]{6}', 'N', 'admin', '2026-08-11 00:00:00',
       '同步遍历时跳过的临时目录，逗号分隔的正则、整体匹配目录名。默认匹配 Transmission 删种时产生的 <种子名>__<6位随机字符> 目录；填 off 关闭该过滤'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.copy.transientdirs');
