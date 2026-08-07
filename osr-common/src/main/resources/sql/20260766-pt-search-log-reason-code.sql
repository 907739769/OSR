-- ----------------------------
-- 20260766: pt_search_log 增加结构化淘汰原因码
--
-- 背景：reason 列存的是带具体数值的自由文本（"做种数 3 低于下限 5"、"体积 xxx 超过上限 yyy"），
-- 同一条规则会产出上百个互不相同的字符串。按 reason 分组统计只会得到一堆计数为 1 的碎片，
-- 而用户真正要问的是「我这 103 个候选主要卡在哪一条规则上」——那必须按规则本身聚合。
--
-- 于是候选全被自己配的过滤规则淘汰时，系统既不在日志里说清楚，通知还一律提示
-- 「检查索引器配置」，把人往完全错误的方向引（索引器好好的，是 freeOnly 或分辨率白名单
-- 把 103 个候选全清了）。
--
-- 补的正是下载失败侧早就有、搜索侧一直缺的对称物：pt_download_record 有 fail_reason_code
-- 且统计面板有失败原因分布，pt_search_log 此前什么都没有。取值见 RejectCode 枚举。
-- 历史数据 reason_code 为 NULL，统计与聚合会自然跳过，不影响既有排查用途。
-- ----------------------------
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_search_log' AND COLUMN_NAME = 'reason_code');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_search_log` ADD COLUMN `reason_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''结构化淘汰原因码，取值见 RejectCode 枚举；摘要类日志与历史数据为空'' AFTER `accepted`', 'SELECT ''Column reason_code already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 统计面板按 (reason_code) 聚合、通知按 (sub_id, id) 回读本次搜索的淘汰分布，
-- 两条路径都只扫被淘汰的行，故索引带上 accepted 前缀
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_search_log' AND INDEX_NAME = 'idx_reason_code');
SET @sql := IF(@idx = 0, 'ALTER TABLE `pt_search_log` ADD INDEX `idx_reason_code`(`accepted`, `reason_code`) USING BTREE', 'SELECT ''Index idx_reason_code already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
