-- ----------------------------
-- 20260806: AI 能力扩展（幂等脚本）
--
-- 1) sys_config 新增 openlist.openai.pt-title-fallback，默认 0（关）：
--    本地正则解析不出的 PT 种子标题交给 AI 兜底。后台排队、结果缓存，不阻塞 RSS 与补搜。
--    需要先配好 openlist.openai.apikey。
--
-- 2) sys_job 新增 104「openliststrm-每周周报」，每周一 9 点，默认暂停（status=1）：
--    汇总最近 7 天的同步 / STRM / 重命名 / PT 下载，配了 OpenAI 时附一段点评，
--    走通知类型 WEEKLY_REPORT。要用的话在「定时任务」页启用即可，也可以点「执行一次」先看效果。
--
-- 自然语言建过滤规则只是一个接口，不需要表结构变更。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'PT 种子标题 AI 兜底解析', 'openlist.openai.pt-title-fallback', '0', 'N', 'admin', '2026-08-06 00:00:00',
       '本地解析不出的种子标题交给 AI 兜底 0-否 1-是。后台排队、结果缓存，下次搜到同一个种子时生效；需先配置 OpenAI API Key'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.openai.pt-title-fallback');

INSERT IGNORE INTO `sys_job` VALUES (104, 'openliststrm-每周周报', 'DEFAULT', 'openListStrmTask.weeklyReport()', '0 0 9 ? * MON', '3', '1', '1', 'admin', '2026-08-06 00:00:00', '', NULL, '');
