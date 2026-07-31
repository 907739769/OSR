-- ----------------------------
-- 20260721: 修复 sys_config.config_value 长度不足问题
-- 重命名文件名模板（rename.filename.template）默认值是完整的 Pebble 模板字符串，长度 549 字符，
-- 超过原 varchar(500)，导致 20260720-rename-category-rule.sql 里的种子 INSERT 报
-- "Data too long for column 'config_value'" 并失败（该行从未成功写入）。
-- 扩展为 varchar(2000) 以容纳更长的模板配置，并补上之前失败的种子数据。
-- ----------------------------

ALTER TABLE `sys_config` MODIFY COLUMN `config_value` varchar(2000) DEFAULT '' COMMENT '参数键值';

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '重命名文件名模板', 'rename.filename.template', '{{ title }} {% if year %} ({{ year }}) {% endif %}/{% if season %}Season {{ season }}/{% endif %}{{ title }} {% if year and not season %} ({{ year }}) {% endif %}{% if season %}S{{ season }}{% endif %}{% if episode %}E{{ episode }}{% endif %}{% if resolution %} - {{ resolution }}{% endif %}{% if source %}.{{ source }}{% endif %}{% if videoCodec %}.{{ videoCodec }}{% endif %}{% if audioCodec %}.{{ audioCodec }}{% endif %}{% if tags is not empty %}.{{ tags|join(\'.\') }}{% endif %}{% if releaseGroup %}-{{ releaseGroup }}{% endif %}.{{ extension }}', 'N', 'admin', '2026-07-21 00:00:00', '重命名文件名模板（Pebble语法），可在"重命名规则设置"页面修改'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'rename.filename.template');
