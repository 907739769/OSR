-- ----------------------------
-- 20260783: 三个通知类型从旧类型里拆出来，路由行按拆分来源继承一份
--
-- 拆分内容（见 NotificationType 枚举上的注释）：
--   HR_STATE            ← DOWNLOAD_COMPLETE   H&R 达标/违规原先挂在「下载完成」「下载失败」下
--   SUBSCRIPTION_SEARCH ← GENERAL             补搜落空原先和索引器故障混在「系统告警」里
--   LIBRARY_STUCK       ← SUBSCRIPTION_HIT    下好没入库/退回缺失/熔断原先挂在「订阅命中」下
--
-- 为什么必须有这条迁移：路由行缺失按「发送」处理（NotifyRouteService#find），
-- 所以「不迁移」在功能上也能跑。但那样一来，一个明确关掉了「下载完成」通知的用户，
-- 升级后会突然开始收到 H&R 达标提醒——他的设置没变，行为却变了。继承一份既保住了
-- 原有意图，也让这三行在配置页上是显式可见、可单独改的。
--
-- HR_STATE 只继承 DOWNLOAD_COMPLETE 而不看 DOWNLOAD_FAILED：违规那条（原 DOWNLOAD_FAILED）
-- 与达标那条（原 DOWNLOAD_COMPLETE）现在合成了一个类型，两个来源给出的开关可能相反，
-- 取任何一个都是猜。取「下载完成」是因为 H&R 通知里绝大多数是达标提醒，且那一档更保守：
-- 用户关掉「下载失败」的情形远少于关掉「下载完成」。
--
-- 逐渠道继承而不是一刀切给默认值：同一个类型在 TG 上开着、在企微上关着是常见配置。
-- recipient_scope 一并继承，理由同上。
--
-- INSERT IGNORE 配 uk_type_channel 保证幂等；来源行不存在的渠道不插行，
-- 留给「路由缺失即发送」兜底，与 20260779 给 Bark/Gotify 的处理一致。
-- ----------------------------

INSERT IGNORE INTO `notify_route` (`notification_type`, `channel`, `enabled`, `recipient_scope`, `create_time`)
SELECT split.new_type, src.`channel`, src.`enabled`, src.`recipient_scope`, NOW()
FROM (
    SELECT 'HR_STATE'            AS new_type, 'DOWNLOAD_COMPLETE' AS from_type UNION ALL
    SELECT 'SUBSCRIPTION_SEARCH',                'GENERAL'                     UNION ALL
    SELECT 'LIBRARY_STUCK',                      'SUBSCRIPTION_HIT'
) split
JOIN `notify_route` src ON src.`notification_type` = split.from_type;
