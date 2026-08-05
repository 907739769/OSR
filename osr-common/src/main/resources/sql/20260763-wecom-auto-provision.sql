-- ----------------------------
-- 20260763: 企微成员自动开号开关
--
-- 背景：绑定表要求每个企微成员对应一个 OSR 账号，但这些人只在企微里用，不会登网页端，
-- 让管理员逐个建号本末倒置。开启本开关后，企微成员首次发指令时后端自动建一个
-- 「影子账号」（sys_user.status='1' 停用 → 无法登录网页端，见 SecurityUserDetailsService）
-- 并完成绑定，管理员零操作。
--
-- 准入边界由企微应用的「可见范围」控制：能给应用发消息的本来就是企业内被授权的成员。
-- 想改成管理员审批制的，把本开关置 0，回到「先在后台建绑定才能用」的行为。
--
-- 每条语句均为幂等，原因见 20260720-rename-category-rule.sql 头部说明。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信-自动开号', 'openlist.wecom.autocreate', '1', 'N', 'admin', '2026-08-05 00:00:00', '1-企微成员首次发指令时自动创建OSR账号并绑定(账号为停用状态，无法登录网页端) 0-必须由管理员在「企业微信用户」页面预先建好绑定'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.wecom.autocreate');
