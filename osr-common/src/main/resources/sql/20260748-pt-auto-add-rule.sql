-- ----------------------------
-- 20260748: 新增热门自动订阅功能（建表 + 菜单）
-- 定期拉取 TMDb 热门/流行榜单，按规则过滤后自动调用既有 PtSubscriptionRestController#subscribe
-- 同一套建订阅流程，数据源做成可插拔接口（PopularSource），为后续接入豆瓣热门榜单预留扩展点，
-- 见 pt_auto_add_rule.source 字段与 doubanId/imdbId 字段（本期只有 TMDb 实现，日志表暂不落这两列）。
-- 菜单：PT下载管理(2070) 下新增第 9 项，menu_id=2073, order_num=9（此前最大 menu_id=2072，
-- 20260745-pt-torrent-blacklist.sql 已占用）。页面与接口同批上线，直接 visible='0'(显示)。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `pt_auto_add_rule` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '规则名称',
    `enabled` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '1' COMMENT '是否启用 0-否 1-是',
    `media_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '媒体类型 TV/MOVIE',
    `source` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '数据源：TMDB_TRENDING_DAY/TMDB_TRENDING_WEEK/TMDB_DISCOVER，未来可扩展 DOUBAN_HOT 等',
    `genre_exclude` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '排除的 TMDb 类型ID，逗号分隔',
    `min_vote_average` decimal(3, 1) NULL DEFAULT NULL COMMENT '最低评分，为空不过滤',
    `min_vote_count` int(10) NULL DEFAULT NULL COMMENT '最低评分人数，为空不过滤',
    `region` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '地区，仅 TMDB_DISCOVER 生效',
    `max_add_per_run` int(10) NOT NULL DEFAULT 5 COMMENT '单轮最多新增几部，防止一次拉爆索引器/下载器',
    `interval_hours` int(10) NOT NULL DEFAULT 24 COMMENT '执行间隔（小时）',
    `downloader_id` int(10) NULL DEFAULT NULL COMMENT '指定下载器，空表示用唯一启用的那个',
    `filter_override` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '建订阅时透传的过滤覆盖(JSON)',
    `last_run_time` datetime(0) NULL DEFAULT NULL COMMENT '上次执行时间',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE
) COMMENT = '热门自动订阅规则';

CREATE TABLE IF NOT EXISTS `pt_auto_add_log` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `rule_id` int(10) NOT NULL COMMENT '规则ID',
    `tmdb_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'TMDb ID',
    `media_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '媒体类型 TV/MOVIE',
    `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '标题',
    `season` int(10) NULL DEFAULT NULL COMMENT '订阅的季号，电影为空',
    `result` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '处理结果 ADDED/SKIPPED_EXISTS/SKIPPED_FILTER/FAILED',
    `message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '附加说明，如跳过原因、失败异常信息',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_rule_id`(`rule_id`) USING BTREE
) COMMENT = '热门自动订阅执行日志';

INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2073, 'PT热门自动订阅', 2070, 9, '/openlist/ptAutoAddRule', '', 'C', '0', '1', 'openliststrm:ptAutoAddRule:view', 'fa fa-fire', 'admin', '2026-07-29 00:00:00', '', NULL, '定期拉取 TMDb 热门/流行榜单，按规则自动建立 PT 订阅');
