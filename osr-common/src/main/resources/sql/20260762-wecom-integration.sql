-- ----------------------------
-- 20260762: 企业微信对接 —— 自建应用配置 / 成员绑定表 / 订阅归属列 / 菜单
--
-- 三块内容：
-- 1) sys_config 新增企微自建应用参数。与 openlist.tg.token 一致按明文存 sys_config
--    （本表的读取方 OpenlistConfig 没有解密环节，若改成 Cipher 密文这里读出来会是乱码）。
-- 2) wecom_user：企微 userid ↔ sys_user 的绑定表。订阅归属、通知定向都靠它把
--    「企微里发消息的这个人」翻译成「OSR 里的这个账号」。
-- 3) pt_subscription.owner_user_id：订阅归属人。允许为 NULL——历史订阅一条归属都没有，
--    非空约束会让老数据无法读取；NULL 语义是「无归属/公共订阅」，所有人可见（见
--    PtSubscriptionRestController#buildQueryWrapper），这样升级后老数据不会凭空消失。
--
-- 每条语句均为幂等，原因见 20260720-rename-category-rule.sql 头部说明。
-- ----------------------------

-- ---------- 1) 企微自建应用配置 ----------
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信-企业ID', 'openlist.wecom.corpid', '', 'N', 'admin', '2026-08-05 00:00:00', '企业微信企业ID(corpid)，在企微管理后台「我的企业」页面查看。留空则企微功能整体不启用'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.wecom.corpid');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信-应用AgentId', 'openlist.wecom.agentid', '', 'N', 'admin', '2026-08-05 00:00:00', '企业微信自建应用的 AgentId，在「应用管理」→ 自建应用详情页查看'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.wecom.agentid');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信-应用Secret', 'openlist.wecom.secret', '', 'N', 'admin', '2026-08-05 00:00:00', '企业微信自建应用的 Secret，与 corpid 一起换取 access_token'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.wecom.secret');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信-回调Token', 'openlist.wecom.token', '', 'N', 'admin', '2026-08-05 00:00:00', '企微应用「接收消息」配置里的 Token，用于回调签名校验。不配则只能发通知、收不到指令'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.wecom.token');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信-回调AESKey', 'openlist.wecom.aeskey', '', 'N', 'admin', '2026-08-05 00:00:00', '企微应用「接收消息」配置里的 EncodingAESKey(43位)，用于回调报文加解密'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.wecom.aeskey');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信-默认接收人', 'openlist.wecom.touser', '@all', 'N', 'admin', '2026-08-05 00:00:00', '无归属通知(如系统告警)的接收人，多个用|分隔，@all 表示应用可见范围内全部成员'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.wecom.touser');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信通知类型过滤', 'openlist.notify.wecom.types', '', 'N', 'admin', '2026-08-05 00:00:00', '逗号分隔的通知类型，留空=不过滤全部发送。可选：GENERAL,SUBSCRIPTION_HIT,DOWNLOAD_COMPLETE,DOWNLOAD_FAILED,EMBY_LIBRARY_SYNC'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.wecom.types');

-- ---------- 2) 企微成员绑定表 ----------
-- wecom_userid 建唯一索引：一个企微成员只能绑一个 OSR 账号，否则「这条消息是谁发的」会有歧义。
-- 反向不设唯一：允许同一个 OSR 账号被多个企微成员绑定（同一人有多个企微号的场景）。
CREATE TABLE IF NOT EXISTS `wecom_user` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `wecom_userid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '企业微信成员 UserId，在企微管理后台「通讯录」成员详情页查看',
    `sys_user_id` bigint(20) NOT NULL COMMENT '绑定的 OSR 用户ID(sys_user.user_id)',
    `sys_user_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'OSR 登录名冗余，仅列表展示用，不参与判定',
    `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '状态 0-正常 1-停用。停用后该成员的指令被拒绝，也不再收到定向通知',
    `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_wecom_userid`(`wecom_userid`) USING BTREE,
    INDEX `idx_sys_user_id`(`sys_user_id`) USING BTREE
) COMMENT = '企业微信成员与 OSR 用户的绑定关系';

-- ---------- 3) 订阅归属列 ----------
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'owner_user_id');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `owner_user_id` bigint(20) NULL DEFAULT NULL COMMENT ''订阅归属人(sys_user.user_id)。NULL=无归属的公共订阅，所有人可见；历史数据全为NULL''', 'SELECT ''Column owner_user_id already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND INDEX_NAME = 'idx_owner_user_id');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD INDEX `idx_owner_user_id`(`owner_user_id`)', 'SELECT ''Index idx_owner_user_id already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4) 菜单 ----------
-- 挂在 系统管理(1) 下，排在「参数设置」(106, order_num=7) 之后：这是账号映射维护，
-- 与 PT/STRM 等业务分组无关。menu_id=2075 已核对 sql/ 目录全部 sys_menu 脚本未被占用。
-- 图标 'fa fa-weixin' 需同步存在于 osr-web/src/composables/useMenuIcon.ts 的映射表中，
-- 否则菜单不显示图标（历史 bug，见 commit 0248e124 / 31a58d53）。
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2075, '企业微信用户', 1, 8, '/system/wecomUser', '', 'C', '0', '1', 'openliststrm:wecomUser:view', 'fa fa-weixin', 'admin', '2026-08-05 00:00:00', '', NULL, '企业微信成员与 OSR 用户的绑定关系维护');
