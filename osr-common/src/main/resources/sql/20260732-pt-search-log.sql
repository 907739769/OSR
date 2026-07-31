-- ----------------------------
-- 20260732: 新增 pt_search_log 表，记录 RSS 轮询/搜索补集时每个候选种子的过滤结果，
-- 供前端排查"这一轮为什么没抓到"。只在候选已匹配到某个订阅时才落库。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `pt_search_log` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `sub_id` int(10) UNSIGNED NOT NULL COMMENT '订阅ID',
    `episode` int(10) NOT NULL COMMENT '集号，电影恒为0，-1表示季包',
    `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'RSS' COMMENT '来源 RSS/SUPPLEMENT',
    `torrent_title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '候选种子标题，摘要类日志为空',
    `indexer_id` int(10) UNSIGNED NULL DEFAULT NULL COMMENT '来源索引器ID，摘要类日志可为空',
    `accepted` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '是否通过过滤 0-否 1-是',
    `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '淘汰原因或摘要说明',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_sub_id`(`sub_id`, `id`) USING BTREE
) COMMENT = 'PT 匹配/过滤日志';
