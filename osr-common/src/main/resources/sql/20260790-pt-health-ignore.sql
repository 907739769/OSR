-- ----------------------------
-- 20260790: 缺集体检的「忽略」
--
-- 背景：一部片源根本不存在的老剧会永远躺在体检列表里，而且按 pt.health.notify-repeat-days
-- （默认 7 天）反复提醒。反复提醒本身是刻意的——20260789 的注释里写得很清楚，
-- 只按"发过就不再发"处理的话，一部永远补不上的剧提醒一次之后就再无声息，而它最该被记住。
-- 但代价是用户没有任何出口说「这条我认了，别再报」，于是整类提醒很快会被整个关掉，
-- 连真正该看的那些一起丢。
--
-- 为什么不用「暂停订阅」代替：暂停会让这条订阅彻底停止工作（RSS 不再匹配、补搜不再跑）。
-- 而这里要表达的是「继续留着，万一哪天有资源还是要抓，只是别再提醒我」——
-- 两者不是一回事，用暂停顶替会让用户为了免打扰而放弃抓取。
--
-- 忽略是订阅级而不是集级：用户的判断是「这部剧补不上」，不是「第 7 集补不上」；
-- 逐集忽略既啰嗦，又会在新集播出时留下一堆需要重新表态的历史。
-- ----------------------------

-- 1. 是否在缺集体检中忽略这条订阅。
--    只影响体检页的可见性与逾期缺集提醒，不影响 RSS 匹配、自动补搜、手动搜索。
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'pt_subscription'
                 AND COLUMN_NAME = 'health_ignored');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_subscription` ADD COLUMN `health_ignored` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT ''0'' COMMENT ''缺集体检是否忽略 0-否 1-是；仅影响体检可见性与逾期提醒，不影响抓取'' AFTER `last_overdue_notify_time`',
    'SELECT ''Column health_ignored already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 忽略的时刻，只作排查用（「这条是什么时候被忽略的」）。
--    不做成"忽略 N 天后自动恢复"：那会让提醒在用户早已忘记的某天突然回来，
--    而他当初的判断（这部剧补不上）多半没有变化。要恢复就显式点「取消忽略」。
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'pt_subscription'
                 AND COLUMN_NAME = 'health_ignored_time');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_subscription` ADD COLUMN `health_ignored_time` datetime(0) NULL DEFAULT NULL COMMENT ''被忽略的时刻，NULL=未忽略'' AFTER `health_ignored`',
    'SELECT ''Column health_ignored_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
