-- ----------------------------
-- 20260726: 修正 pt_download_record 与 pt_filter_config 的两处设计缺陷
--
-- 为什么用 DROP TABLE + CREATE TABLE 而不是 ALTER TABLE ADD COLUMN / MODIFY：
-- 这两张表由 20260725-pt-subscription.sql 刚建，实测确认 pt_download_record 为 0 行、
-- pt_filter_config 只有 id=1 一行未被修改过的默认种子数据，且当前均无任何调用方读写。
-- 在这个前提下重建是真正幂等的（DROP TABLE IF EXISTS 永远成功，CREATE TABLE 永远重建出
-- 相同结构），而 MySQL 的 ALTER TABLE ADD COLUMN 没有 IF NOT EXISTS 语法，重跑会因列已存在
-- 而报错，反而不幂等。若这两张表将来有数据或调用方，就不能再用这种方式改表。
-- ----------------------------

DROP TABLE IF EXISTS `pt_download_record`;
CREATE TABLE `pt_download_record` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `sub_id` int(10) UNSIGNED NOT NULL COMMENT '订阅ID',
    `episode` int(10) NOT NULL COMMENT '集号；电影恒为0；-1表示整季包(一个种子含多集，此时该订阅所有MISSING的集共同指向这条记录)',
    `indexer_id` int(10) UNSIGNED NOT NULL COMMENT '来源索引器ID',
    `guid` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '索引器给出的条目唯一标识(RSS guid)',
    `guid_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'guid的SHA-256十六进制，用于唯一索引(guid本身过长无法直接建索引)',
    `torrent_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '种子hash，从下载器回填，仅供排查',
    `tracking_tag` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '推送时打的唯一标签 osr-pt-{id}，用于回映下载器中的种子',
    `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '原始种子标题',
    `size` bigint(20) NOT NULL DEFAULT 0 COMMENT '体积(字节)',
    `seeders` int(10) NOT NULL DEFAULT 0 COMMENT '做种数',
    `downloader_id` int(10) UNSIGNED NULL DEFAULT NULL COMMENT '推送到的下载器ID',
    `state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PUSHED' COMMENT '状态 PUSHED/DOWNLOADING/COMPLETED/FAILED',
    `fail_reason` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '失败原因',
    `pushed_time` datetime(0) NULL DEFAULT NULL COMMENT '推送时间',
    `completed_time` datetime(0) NULL DEFAULT NULL COMMENT '完成时间',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_indexer_guid`(`indexer_id`, `guid_hash`) USING BTREE,
    INDEX `idx_sub_episode`(`sub_id`, `episode`) USING BTREE,
    INDEX `idx_state`(`state`) USING BTREE,
    -- 原 idx_tracking_tag 改为 UNIQUE：tracking_tag 是"下载记录 ↔ 下载器中种子"的回映唯一键，
    -- 普通索引挡不住重复；重复 tag 会导致把一个种子的状态写到另一条记录上
    UNIQUE INDEX `uk_tracking_tag`(`tracking_tag`) USING BTREE
) COMMENT = 'PT 下载记录';

DROP TABLE IF EXISTS `pt_filter_config`;
CREATE TABLE `pt_filter_config` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键，本表恒定只有一行(id=1)',
    `min_seeders` int(10) NOT NULL DEFAULT 1 COMMENT '最低做种数，低于此值淘汰',
    `min_size` bigint(20) NOT NULL DEFAULT 0 COMMENT '最小体积(字节)，0表示不限',
    `max_size` bigint(20) NOT NULL DEFAULT 0 COMMENT '最大体积(字节)，0表示不限',
    `free_only` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '是否仅下载免费种 0-否 1-是',
    `include_keywords` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '逗号分隔，标题须命中其一，空表示不限',
    `exclude_keywords` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '逗号分隔，标题命中任一则淘汰',
    `resolution_priority` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '2160p,1080p,720p' COMMENT '分辨率优先级，逗号分隔，越靠前越优先',
    `resolution_whitelist` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '分辨率白名单，逗号分隔，空表示不限。与resolution_priority不同：这是硬性过滤(不在白名单里的直接淘汰)，priority只影响排序',
    `sort_priority` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'RESOLUTION,FREE,SEEDERS,SIZE' COMMENT '排序维度顺序，逗号分隔',
    `preferred_size` bigint(20) NOT NULL DEFAULT 0 COMMENT '体积接近度的目标值(字节)，0表示该维度不参与比较',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE
) COMMENT = 'PT 全局过滤与排序配置(单行)';

-- 种子数据：显式主键 + INSERT IGNORE 保证幂等，缺了这行引擎读不到配置。
-- 取值与 20260725-pt-subscription.sql 一致，resolution_whitelist 留空(默认不限)。
INSERT IGNORE INTO `pt_filter_config` (`id`, `min_seeders`, `min_size`, `max_size`, `free_only`, `include_keywords`, `exclude_keywords`, `resolution_priority`, `resolution_whitelist`, `sort_priority`, `preferred_size`, `create_time`) VALUES
(1, 1, 0, 0, '0', NULL, '预告,花絮,samples', '2160p,1080p,720p', NULL, 'RESOLUTION,FREE,SEEDERS,SIZE', 0, '2026-07-25 00:00:00');
