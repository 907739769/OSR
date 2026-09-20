-- ----------------------------
-- 20260797: 媒体服务器的连通状态三列（幂等脚本）
--
-- 媒体服务器是订阅「已入库」判定的唯一数据来源，而它挂掉的<b>全部</b>可见症状是
-- 「订阅进度不动」——配置页上此前一个信号都没有（三行写的是 类型 / 地址 / 创建时间）。
-- 隔壁索引器至少有 fail_count 可看，这里什么都没有。
--
-- 状态<b>被动</b>采集：对账（SubscriptionService#queryLibrary）每次真正用到某台服务器时
-- 把结果写回，零额外请求，且记下的正是「业务实际用它时通不通」——这与另起一个周期任务去打
-- /System/Info 并不等价（探针通了不代表查得到数据），而后者恰恰是卡死清扫将来要的判据。
-- 写回本身有节流，见 MediaServerHealthRecorder，不会每条订阅刷一次 UPDATE。
--
-- last_check_ok 允许为 NULL：新装或刚加的服务器「还没被用到过」，与「用过且不通」是两回事，
-- 默认成 '0' 会让一台完全正常的新服务器在页面上显示成红色的「不可用」。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_media_server' AND COLUMN_NAME = 'last_check_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_media_server` ADD COLUMN `last_check_time` datetime(0) NULL DEFAULT NULL COMMENT ''上次被业务实际访问的时间，NULL 表示还没用到过'' AFTER `enabled`', 'SELECT ''Column last_check_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_media_server' AND COLUMN_NAME = 'last_check_ok');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_media_server` ADD COLUMN `last_check_ok` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''上次访问结果 1-连通 0-失败，NULL-还没用到过'' AFTER `last_check_time`', 'SELECT ''Column last_check_ok already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_media_server' AND COLUMN_NAME = 'last_check_error');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_media_server` ADD COLUMN `last_check_error` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''上次访问失败的原因，连通时清空'' AFTER `last_check_ok`', 'SELECT ''Column last_check_error already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
