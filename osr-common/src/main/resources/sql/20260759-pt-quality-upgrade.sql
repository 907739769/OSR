-- ----------------------------
-- 20260759: 洗版（quality upgrade）第一期
--
-- 背景：集一旦入库就再也不会被更好的资源替换。现有的"洗版"是 SubscriptionService#resetEpisode
-- 手工把某集重置成 MISSING 让它重走一轮下载——但系统对"库里现在躺的是什么货色"毫无记忆，
-- 重下的版本完全可能比原来的更差。
--
-- pt_subscription_episode.quality 这一列建表时就写着"为洗版预留"，一直没被写过，本次启用：
-- 存一份质量画像快照（JSON），来源是当初填满这一集的下载记录标题的本地解析结果。
-- 原 varchar(32) 装不下 JSON，扩到 255。
--
-- 第一期只做"识别 + 下载 + 通知"，不碰旧文件——OSR 从不删种，新旧两个版本会同时存在，
-- 清理由用户手动完成。自动清理留到第二期，且必须先过 H&R 达标检查（删掉还在考核期内的
-- 旧种子的文件，等于亲手制造一次 H&R 记过）。
--
-- 幂等：逐列判断存在与否 + CREATE TABLE IF NOT EXISTS + INSERT IGNORE。
-- pt_upgrade_config 的种子数据里 enabled='0'，因此本迁移对既有部署是行为无变化的。
-- ----------------------------

-- quality 从 varchar(32) 扩到 255 装 JSON 画像。MODIFY 是幂等的（重复执行结果相同），
-- 但仍先判一次当前长度，避免在已经是 255 的库上做无谓的表重建。
SET @len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription_episode' AND COLUMN_NAME = 'quality');
SET @sql := IF(@len IS NOT NULL AND @len < 255, 'ALTER TABLE `pt_subscription_episode` MODIFY COLUMN `quality` VARCHAR(255) DEFAULT NULL COMMENT ''已入库版本的质量画像快照(JSON)，洗版判定的基线''', 'SELECT ''Column quality already wide enough''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription_episode' AND COLUMN_NAME = 'upgrade_state');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription_episode` ADD COLUMN `upgrade_state` VARCHAR(16) DEFAULT NULL COMMENT ''洗版状态 PENDING=可升级 / REACHED=已达目标质量 / NO_BASELINE=无质量基线不参与；NULL=尚未评估'' AFTER `quality`', 'SELECT ''Column upgrade_state already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 扫描任务每轮按 (state, upgrade_state) 捞待洗版的集，剧集集数多、订阅数会增长，不建索引会退化成全表扫描
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription_episode' AND INDEX_NAME = 'idx_state_upgrade');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription_episode` ADD INDEX `idx_state_upgrade`(`state`, `upgrade_state`) USING BTREE', 'SELECT ''Index idx_state_upgrade already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'upgrade_enabled');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `upgrade_enabled` CHAR(1) NOT NULL DEFAULT ''1'' COMMENT ''该订阅是否参与洗版 0-否 1-是；全局开关关闭时本项无效''', 'SELECT ''Column upgrade_enabled already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `pt_upgrade_config` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键，本表恒定只有一行(id=1)',
    `enabled` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '洗版总开关 0-否 1-是。默认关闭：开启前用户必须先确认目标质量，否则每集都会永远搜下去',
    -- 只放"哪些维度参与比较、按什么顺序"，各维度内部"谁比谁好"的优先级列表复用 pt_filter_config
    -- (resolution_priority / source_priority / release_group_priority)，避免两处配置对"什么更好"说法不一致
    `quality_priority` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'RESOLUTION,SOURCE,TAG,RELEASE_GROUP' COMMENT '洗版比较的维度顺序，逗号分隔。刻意不含 SEEDERS/SIZE/FREE——那些不是画质，会导致无限洗版',
    `target_resolution` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '2160p' COMMENT '目标分辨率(cutoff)，达到即停止洗版；空表示该项不约束',
    `target_sources` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT 'REMUX,BluRay' COMMENT '目标媒介来源(cutoff)，逗号分隔，命中其一即满足该项；空表示不约束',
    `target_tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '目标质量标签(cutoff)，逗号分隔，须全部具备；空表示不约束',
    `max_concurrent` int(10) NOT NULL DEFAULT 2 COMMENT '洗版同时在途的下载数上限。独立于补缺集：缺集是刚需，洗版是锦上添花，不能抢名额',
    `scan_interval_hours` int(10) NOT NULL DEFAULT 6 COMMENT '洗版扫描周期(小时)',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE
) COMMENT = 'PT 洗版(质量升级)配置(单行)';

-- 种子数据：显式主键 + INSERT IGNORE 保证幂等。enabled='0'，用户到配置页确认目标质量后再开
INSERT IGNORE INTO `pt_upgrade_config` (`id`, `enabled`, `quality_priority`, `target_resolution`, `target_sources`, `target_tags`, `max_concurrent`, `scan_interval_hours`, `create_time`) VALUES
(1, '0', 'RESOLUTION,SOURCE,TAG,RELEASE_GROUP', '2160p', 'REMUX,BluRay', NULL, 2, 6, '2026-08-04 00:00:00');
