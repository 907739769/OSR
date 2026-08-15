-- ----------------------------
-- 20260784: 转移做种（把下载器 A 里已完成的种子搬到下载器 B 继续做种）
--
-- 背景：用户此前靠 IYUU 把 qBittorrent 下完的种子转移到 Transmission 保种。转移这件事
-- OSR 自己就能做——两个下载器客户端都在，SEED_ONLY 角色本来就是为"接转移过来的种子"设的，
-- 跨下载器的 H&R 追踪（DownloaderSnapshot）也早就能认回换了机器的种子。
--
-- 注意本功能只覆盖 IYUU 的「转移」，不覆盖「辅种」：辅种要拿 infohash 去其它站点找同一份
-- 资源，依赖 IYUU 服务端的站点索引与各站 passkey，OSR 没有也造不出这些数据。
--
-- 两张表：
--   pt_transfer_rule   —— 一条规则 = 一对「源下载器 → 目标下载器」+ 筛选条件。
--   pt_transfer_record —— 每次转移的过程记录。转移是跨轮次的状态机（加种 → 校验 →
--                          启动 → 删源种），中途要跨多个调度周期，状态必须落库；
--                          只放内存的话进程一重启，目标端就留下一堆暂停态的孤儿种子。
--
-- 「OSR 从不删种」在此开第三个受控例外（前两个是 DownloadTrackService#removeUselessTorrent
-- 与 TorrentCleanService）：转移成功后删源端的种子。护栏见 TorrentTransferService——
-- 只在目标端校验到 100% 之后删、deleteFiles 恒为 false（文件要留给目标端做种）、
-- delete_source 可逐规则关掉。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `pt_transfer_rule` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `name` varchar(64) NOT NULL COMMENT '规则名，仅用于展示',
  `source_downloader_id` int NOT NULL COMMENT '源下载器ID（种子从这里搬走）',
  `target_downloader_id` int NOT NULL COMMENT '目标下载器ID（种子搬到这里继续做种）',
  `enabled` char(1) NOT NULL DEFAULT '0' COMMENT '是否启用 0-否 1-是；默认关闭，必须显式开启',
  `min_seed_hours` int NOT NULL DEFAULT 72 COMMENT '源下载器上最短做种时长(小时)，达到才转移；0表示不限',
  `min_size_gb` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '体积区间下界(GB，含)',
  `max_size_gb` decimal(10,2) NULL DEFAULT NULL COMMENT '体积区间上界(GB，不含)，NULL表示不限',
  `include_tags` varchar(255) NULL DEFAULT NULL COMMENT '只转移带其中任一标签的种子，逗号分隔；为空表示不限',
  `exclude_tags` varchar(255) NULL DEFAULT NULL COMMENT '带其中任一标签的种子永不转移，逗号分隔',
  `path_mapping` varchar(1024) NULL DEFAULT NULL COMMENT '保存路径前缀映射，JSON数组如[{"from":"/downloads","to":"/data/downloads"}]；两个下载器挂载一致时留空',
  `target_tag` varchar(64) NULL DEFAULT NULL COMMENT '在目标下载器上打的标签，便于识别转移来的种子；为空则不打标签',
  `delete_source` char(1) NOT NULL DEFAULT '1' COMMENT '目标端校验通过后是否删除源下载器上的种子 0-否 1-是；无论取值如何都不会删除文件',
  `max_per_round` int NOT NULL DEFAULT 10 COMMENT '单轮最多发起多少个转移，0表示不限；防止一次把整台机器的种子全推过去',
  `verify_timeout_minutes` int NOT NULL DEFAULT 120 COMMENT '目标端校验超时(分钟)，超时判定转移失败并撤销目标端的种子',
  `remark` varchar(255) NULL DEFAULT NULL COMMENT '备注',
  `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_source` (`source_downloader_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'PT 转移做种规则' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `pt_transfer_record` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `rule_id` int NOT NULL COMMENT '所属规则ID',
  `torrent_hash` varchar(64) NOT NULL COMMENT '种子 infohash，小写',
  `torrent_name` varchar(512) NULL DEFAULT NULL COMMENT '种子名，仅用于展示与排查',
  `size_bytes` bigint NOT NULL DEFAULT 0 COMMENT '种子体积(字节)',
  `source_downloader_id` int NOT NULL COMMENT '源下载器ID',
  `target_downloader_id` int NOT NULL COMMENT '目标下载器ID',
  `source_save_path` varchar(512) NULL DEFAULT NULL COMMENT '源下载器上的保存路径',
  `target_save_path` varchar(512) NULL DEFAULT NULL COMMENT '目标下载器上的保存路径(已应用路径映射)',
  `state` varchar(20) NOT NULL COMMENT '状态：VERIFYING-目标端校验中 COMPLETED-已完成 FAILED-失败 SKIPPED-目标端已存在',
  `fail_reason` varchar(512) NULL DEFAULT NULL COMMENT '失败原因，失败时必填',
  `source_deleted` char(1) NOT NULL DEFAULT '0' COMMENT '源下载器上的种子是否已删除 0-否 1-是（从不删除文件）',
  `verify_start_time` datetime(0) NULL DEFAULT NULL COMMENT '目标端开始校验的时间，用于判定校验超时',
  `finish_time` datetime(0) NULL DEFAULT NULL COMMENT '转移终结(完成或失败)的时间',
  `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_state` (`state`) USING BTREE,
  INDEX `idx_hash` (`torrent_hash`) USING BTREE,
  INDEX `idx_create_time` (`create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'PT 转移做种记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 菜单：挂在 PT下载管理(2070) 下，排在"追剧日历"(2076, order_num=11)之后。
-- menu_id=2078：已核对 sql/ 目录下全部涉及 sys_menu 的脚本，当前最大为 2077，2078 未被占用。
-- icon 直接写 mdi 名（20260780 已把库里全部图标换成 mdi，不要再写 fa fa-*）。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2078, 'PT转移做种', 2070, 12, '/openlist/ptTransferRule', '', 'C', '0', '1', 'openliststrm:ptTransferRule:view', 'mdi-swap-horizontal', 'admin', '2026-08-15 00:00:00', '', NULL, 'PT 转移做种：把下载器A里已完成的种子搬到下载器B继续做种（IYUU 转移的替代）');
