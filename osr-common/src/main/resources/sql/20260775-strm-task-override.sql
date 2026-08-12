-- ----------------------------
-- 20260775: STRM 任务级配置覆盖
-- openlist_strm_task 此前只有 path/status，生成行为（输出目录、是否下字幕、最小体积）
-- 全部取自 sys_config 全局值，多媒体库场景下没法分别配：外语片库要字幕、国产剧库不要，
-- 电影库要滤掉花絮、纪录片库阈值又不同。
-- 存储形态照 pt_subscription.filter_override 的路子：单列 JSON，只有出现在 JSON 里的
-- 键才覆盖，没出现的沿用全局值，因此该列为 NULL / 空串时行为与本次改动前完全一致。
-- 可覆盖键：outputDir(字符串) / downloadSub("0"|"1") / minFileSize(数字，单位 MB)。
-- 刻意不含 encode 与扩展名：它们有解码侧消费者（RenameOrphanScanServiceImpl、
-- RenameCleanupService、StrmSourcePathResolver 要从 .strm 内容反解网盘路径），
-- 且本质是「播放器吃什么」的全站属性，没有分库配置的合理场景。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'openlist_strm_task'
                 AND COLUMN_NAME = 'strm_override');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `openlist_strm_task` ADD COLUMN `strm_override` varchar(1000) NULL DEFAULT NULL COMMENT ''任务级配置覆盖(JSON)，键：outputDir/downloadSub/minFileSize，未出现的键沿用全局配置'' AFTER `strm_task_status`',
    'SELECT ''Column strm_override already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
