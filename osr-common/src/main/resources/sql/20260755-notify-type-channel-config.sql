-- ----------------------------
-- 20260755: 新增通知类型按渠道过滤配置，配合 notify/NotificationType 使用
-- 留空＝不过滤，该渠道收到全部类型（向后兼容原始行为，不填不影响现有通知）
-- 可选值（逗号分隔，忽略大小写）：GENERAL,SUBSCRIPTION_HIT,DOWNLOAD_COMPLETE,DOWNLOAD_FAILED,EMBY_LIBRARY_SYNC
-- 采用 INSERT ... WHERE NOT EXISTS 保证幂等，已存在的键不会被覆盖。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'Telegram通知类型过滤', 'openlist.notify.tg.types', '', 'N', 'admin', '2026-08-02 00:00:00', '逗号分隔的通知类型，留空=不过滤全部发送。可选：GENERAL,SUBSCRIPTION_HIT,DOWNLOAD_COMPLETE,DOWNLOAD_FAILED,EMBY_LIBRARY_SYNC'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.tg.types');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'Webhook通知类型过滤', 'openlist.notify.webhook.types', '', 'N', 'admin', '2026-08-02 00:00:00', '逗号分隔的通知类型，留空=不过滤全部发送。可选：GENERAL,SUBSCRIPTION_HIT,DOWNLOAD_COMPLETE,DOWNLOAD_FAILED,EMBY_LIBRARY_SYNC'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.webhook.types');
