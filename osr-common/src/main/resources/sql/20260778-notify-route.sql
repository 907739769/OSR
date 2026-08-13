-- ----------------------------
-- 20260778: 通知路由表 —— 通知类型 × 渠道 × 收件人范围
--
-- 此前每个渠道一行 sys_config，值是逗号分隔的类型名（openlist.notify.tg.types 等）。
-- 三个渠道还能忍，渠道一多就没法看也没法配；而且完全没有「发给谁」这一维——
-- 企微的分人投递是写死在 WeComNotifier 里的行为，用户配不了。
--
-- recipient_scope 三档：
--   ADMIN 只发渠道的默认接收人（企微 openlist.wecom.touser / TG openlist.tg.userid）
--   OWNER 只发订阅归属人；无归属（系统级告警、历史订阅 owner_user_id 为 NULL）回退默认接收人
--         ——否则索引器故障这类告警会静默消失
--   BOTH  归属人 + 默认接收人，同一人时去重
-- 该维度只对支持分人投递的渠道有意义（目前仅企业微信）。TG/Webhook 只有一个全局接收人，
-- 存什么都按 ADMIN 表现，页面上也不给它们展示该选项，避免给出不生效的开关。
--
-- 下面的数据迁移把现有三个 sys_config 逐类型展开成路由行，保证升级后行为逐字段不变：
--   types 为空 = 该渠道当时不过滤、全部类型都发 → 全部 enabled='1'
--   types 非空 = 只有列出的类型发            → FIND_IN_SET 命中的才 enabled='1'
--   企微迁成 OWNER（保住它现有的分人行为），TG/Webhook 迁成 ADMIN（它们本就只有一个接收人）
-- ----------------------------

CREATE TABLE IF NOT EXISTS `notify_route` (
    `id`                bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `notification_type` varchar(32)  NOT NULL COMMENT '通知类型，取值见 NotificationType 枚举',
    `channel`           varchar(32)  NOT NULL COMMENT '渠道标识，取值见 INotifier#channelKey',
    `enabled`           char(1)      NOT NULL DEFAULT '1' COMMENT '0-关闭 1-开启',
    `recipient_scope`   varchar(16)  NOT NULL DEFAULT 'ADMIN' COMMENT '收件人范围 ADMIN/OWNER/BOTH，仅对支持分人投递的渠道生效',
    `create_time`       datetime(0)  NULL DEFAULT NULL COMMENT '创建时间',
    `update_time`       datetime(0)  NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_type_channel` (`notification_type`, `channel`) USING BTREE
) COMMENT = '通知路由：类型×渠道×收件人范围' ROW_FORMAT = Dynamic;

-- 五种通知类型 × 三个渠道 = 15 行。INSERT IGNORE 配唯一键保证幂等，重复执行不会覆盖用户后来的改动
INSERT IGNORE INTO `notify_route` (`notification_type`, `channel`, `enabled`, `recipient_scope`, `create_time`)
SELECT t.name, ch.channel,
       CASE
           WHEN c.config_value IS NULL OR TRIM(c.config_value) = '' THEN '1'
           WHEN FIND_IN_SET(t.name, REPLACE(c.config_value, ' ', '')) > 0 THEN '1'
           ELSE '0'
       END,
       ch.scope,
       NOW()
FROM (
    SELECT 'GENERAL' AS name UNION ALL
    SELECT 'SUBSCRIPTION_HIT' UNION ALL
    SELECT 'DOWNLOAD_COMPLETE' UNION ALL
    SELECT 'DOWNLOAD_FAILED' UNION ALL
    SELECT 'EMBY_LIBRARY_SYNC'
) t
CROSS JOIN (
    SELECT 'TELEGRAM' AS channel, 'openlist.notify.tg.types'      AS cfg_key, 'ADMIN' AS scope UNION ALL
    SELECT 'WEBHOOK',              'openlist.notify.webhook.types',           'ADMIN' UNION ALL
    SELECT 'WECOM',                'openlist.notify.wecom.types',             'OWNER'
) ch
LEFT JOIN `sys_config` c ON c.`config_key` = ch.cfg_key;

-- 通知路由菜单，挂在系统管理(1)下
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2077, '通知路由', 1, 9, '/system/notifyRoute', '', 'C', '0', '1', 'openliststrm:notifyRoute:view', 'fa fa-bell-o', 'admin', '2026-08-12 00:00:00', '', NULL, '配置每种通知类型发送到哪些渠道、发给谁');
