-- ----------------------------
-- 20260740: 新增通用 Webhook 通知渠道配置项，配合 notify/WebhookNotifier 使用
-- 采用 INSERT ... WHERE NOT EXISTS 保证幂等，已存在的键不会被覆盖。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '通知Webhook地址', 'openlist.notify.webhook.url', '', 'N', 'admin', '2026-07-24 00:00:00', '通用 Webhook 通知地址，POST JSON {"text": message}，留空则不启用'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.webhook.url');
