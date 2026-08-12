-- ----------------------------
-- 20260777: 每集记录对应的 TMDb 集号，用于与媒体库对账
--
-- 背景：三套编号并存。PT 种子与 OSR 都用「季内相对集号」(第23季第13集)，
-- TMDb 主数据对长篇动画用绝对集号（航海王第23季 = 1156..1181），
-- 而媒体库按刮削结果组织——实测用户的 Emby 把航海王全部 1172 集平铺在第 1 季，
-- IndexNumber 就是绝对集号。
--
-- 于是 listEpisodes(tmdbId, season=23) 问 Emby「第 23 季有哪些集」永远返回空，
-- 26 集里有 11 集明明已经下载完成，状态却一直卡在 MISSING。
--
-- 存下 TMDb 集号就补上了缺的那一环：本地第 13 集 ↔ TMDb 1168 ↔ Emby S01E1168。
-- 由 EpisodeAirDateSyncTask 与播出日期同一批写入（对齐逻辑见 TmdbEpisodeAligner）。
-- 普通剧集该值与 episode 相同，对账行为不变。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'pt_subscription_episode'
                 AND COLUMN_NAME = 'tmdb_episode_number');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_subscription_episode` ADD COLUMN `tmdb_episode_number` int NULL DEFAULT NULL COMMENT ''该集在 TMDb 上的集号。普通剧集与 episode 相同；长篇动画是绝对集号，用于与按绝对编号组织的媒体库对账'' AFTER `air_date`',
    'SELECT ''Column tmdb_episode_number already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
