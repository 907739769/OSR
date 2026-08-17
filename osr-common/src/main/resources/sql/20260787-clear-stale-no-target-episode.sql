-- 清理「集号解析 bug」留下的过期 NO_TARGET_EPISODE 失败记录
--
-- 背景：NO_TARGET_EPISODE 的语义是「下载器给出的文件列表里一个目标集都没有」，被定为不可重试
-- （见 FailReasonCode），因此 SubscriptionEngine#excludeAlreadyRecorded 会把对应种子对该索引器
-- 永久排除。这个判断本身是对的——判据来自下载器的真实文件列表，是全流程最精确的一次。
--
-- 但对使用绝对集号的剧（航海王一类长篇动画：本地第 19 集 = TMDb 第 1174 集），种子内文件名写的是
-- 绝对号（One Piece S01E1174.mkv），而目标集是本地号，两边交不上被误判成「不含目标集」。
-- 该 bug 已由 AbsoluteEpisodeMap#toLocalOrSelf 在 DownloadTrackService 侧修复，但此前写进库里的
-- 结论原样留着，把一批本来能下的种子永久封死，用户表现为「手动搜到了资源、点推送却一直失败」。
--
-- 只删「该订阅确实存在绝对编号」的那些记录：tmdb_episode_number 与 episode 不等，正是
-- AbsoluteEpisodeMap#from 判定「这部剧用绝对编号」的同一条判据。普通剧集的 NO_TARGET_EPISODE
-- 是准确的，一条都不动。
--
-- 删而不是改 fail_reason_code：这些记录的内容本身是错的（写着「种子内不含任何目标集」，
-- 而实际含），留着只会在下载记录页误导排查；它们也不承载别的状态——集侧的 download_id 在
-- 回退时已置空（见 releaseInFlightEpisodes）。
DELETE FROM pt_download_record
WHERE state = 'FAILED'
  AND fail_reason_code = 'NO_TARGET_EPISODE'
  AND sub_id IN (
      SELECT sub_id FROM (
          SELECT DISTINCT sub_id
          FROM pt_subscription_episode
          WHERE tmdb_episode_number IS NOT NULL
            AND tmdb_episode_number <> episode
      ) AS absolute_numbered_subs
  );
