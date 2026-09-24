-- ----------------------------
-- 20260807: 保种与上传量看板的每日快照（幂等脚本）
--
-- 每台下载器每天一行，SeedSnapshotTask 每小时覆盖当天那一行（最后一次即当日收盘值）。
-- 每日上传量 = 当天 cumulative_uploaded − 前一天的；它是下载器自己记的单调计数器
-- （qB alltime_ul / Transmission cumulative-stats.uploadedBytes），删种不会让它变小。
-- uploaded_sum 是现存种子上传量之和，删种会变小，只在计数器取不到时兜底。
-- 只增不删的统计表，一台下载器一年 365 行，不设保留策略。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `pt_seed_snapshot` (
    `id`                  bigint     NOT NULL AUTO_INCREMENT,
    `downloader_id`       int        NOT NULL COMMENT '下载器 id',
    `snapshot_date`       date       NOT NULL COMMENT '快照日期',
    `torrent_count`       int        NOT NULL DEFAULT 0 COMMENT '种子总数',
    `seeding_count`       int        NOT NULL DEFAULT 0 COMMENT '已下完（保种中）的种子数',
    `seeding_size`        bigint     NOT NULL DEFAULT 0 COMMENT '已下完种子的体积之和（字节）',
    `uploaded_sum`        bigint     NOT NULL DEFAULT 0 COMMENT '现存种子上传量之和（字节），删种会变小',
    `cumulative_uploaded` bigint     NULL DEFAULT NULL COMMENT '下载器累计上传（字节），单调递增；取不到为空',
    `update_time`         datetime   NULL DEFAULT NULL COMMENT '最后一次覆盖时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_downloader_date`(`downloader_id`, `snapshot_date`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'PT 保种看板每日快照' ROW_FORMAT = Dynamic;
