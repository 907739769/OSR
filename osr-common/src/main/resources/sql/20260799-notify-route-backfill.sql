-- ----------------------------
-- 20260799: 把 notify_route 补齐成完整的「类型 × 渠道」矩阵
--
-- 表里缺行的格子，分发时按「开启 + 原样投递」处理（见 NotifyRouteService#find）。
-- 但有三批格子一直没有行：
--   * EPISODE_OVERDUE（缺集逾期）新增时没配迁移，五个渠道全缺
--   * BARK / GOTIFY 是在 20260778 之后加的渠道，20260779 只插了配置、没插路由；
--     20260783 拆分类型时又只从已有行复制，这两个渠道仍然全缺
-- 配置页原先在前端给缺行格子补「仅管理员」，而企微缺行的真实行为是「仅订阅人」，
-- 用户随手一保存就把缺集提醒改成了只发管理员。页面的默认值已改由后端按真实行为给出，
-- 这里再把行补上，让表里的数据与实际发送行为逐格一致，排查时不必再记「缺行=什么」。
--
-- 取值与缺行时的行为完全相同，因此升级前后行为不变：
--   enabled = '1'；recipient_scope 对支持分人的企微是 OWNER，其余渠道只有一个接收人，写 ADMIN。
-- INSERT IGNORE 配 uk_type_channel：已有的行（用户改过的配置）一律不动，重复执行幂等。
-- ----------------------------

INSERT IGNORE INTO `notify_route` (`notification_type`, `channel`, `enabled`, `recipient_scope`, `create_time`)
SELECT t.name, ch.channel, '1', ch.scope, NOW()
FROM (
    SELECT 'GENERAL' AS name UNION ALL
    SELECT 'SUBSCRIPTION_HIT' UNION ALL
    SELECT 'DOWNLOAD_COMPLETE' UNION ALL
    SELECT 'DOWNLOAD_FAILED' UNION ALL
    SELECT 'EMBY_LIBRARY_SYNC' UNION ALL
    SELECT 'HR_STATE' UNION ALL
    SELECT 'SUBSCRIPTION_SEARCH' UNION ALL
    SELECT 'LIBRARY_STUCK' UNION ALL
    SELECT 'EPISODE_OVERDUE'
) t
CROSS JOIN (
    SELECT 'TELEGRAM' AS channel, 'ADMIN' AS scope UNION ALL
    SELECT 'WEBHOOK', 'ADMIN' UNION ALL
    SELECT 'WECOM',   'OWNER' UNION ALL
    SELECT 'BARK',    'ADMIN' UNION ALL
    SELECT 'GOTIFY',  'ADMIN'
) ch;
