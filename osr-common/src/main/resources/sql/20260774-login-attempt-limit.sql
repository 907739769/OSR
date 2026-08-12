-- ----------------------------
-- 20260774: 登录失败次数限制（防爆破）
-- 原先登录接口只有一份手工维护的 IP 黑名单 sys.login.blackIPList，没有任何失败次数限制，
-- 弱口令可被无限次尝试。这里补三个阈值配置，实现见 LoginAttemptService。
-- 采用 INSERT ... WHERE NOT EXISTS 保证幂等，已存在的键不会被覆盖。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '登录失败锁定次数（账号）', 'sys.login.maxRetryCount', '5', 'Y', 'admin', '2026-08-12 00:00:00',
       '同一账号连续登录失败达到该次数后临时锁定。填 0 关闭账号锁定'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'sys.login.maxRetryCount');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '登录失败锁定次数（IP）', 'sys.login.ipMaxRetryCount', '30', 'Y', 'admin', '2026-08-12 00:00:00',
       '同一来源 IP 连续登录失败达到该次数后临时锁定，用于拦换着用户名喷的扫描器。阈值刻意放宽，避免同一出口 IP 下的正常用户被连坐。填 0 关闭 IP 锁定'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'sys.login.ipMaxRetryCount');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '登录锁定时长（分钟）', 'sys.login.lockMinutes', '10', 'Y', 'admin', '2026-08-12 00:00:00',
       '触发锁定后的锁定时长，同时也是失败次数的统计窗口（距上次失败超过该时长则计数清零）。填 0 关闭全部登录锁定'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'sys.login.lockMinutes');
