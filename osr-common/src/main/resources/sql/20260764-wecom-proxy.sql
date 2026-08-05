-- ----------------------------
-- 20260764: 企业微信 API 代理地址
--
-- 背景：2022-06-20 之后创建的自建应用，调用企微 API 必须先在企微后台登记「企业可信IP」。
-- 家宽/动态 IP 的部署登记不了固定 IP，通行做法是在一台有固定 IP 的机器（或
-- Cloudflare Worker 之类）上反代 qyapi.weixin.qq.com，把该中转地址填到这里。
-- 2022-06-20 之前创建的应用没有可信IP要求，保持默认值即可。
--
-- 默认值是官方地址：留空或填了非法值时 WeComApiClient 也会回退到它，
-- 所以「不配代理」在任何情况下都能正常工作。
--
-- 每条语句均为幂等，原因见 20260720-rename-category-rule.sql 头部说明。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '企业微信-API代理地址', 'openlist.wecom.proxy', 'https://qyapi.weixin.qq.com', 'N', 'admin', '2026-08-05 00:00:00', '企微API的中转地址，仅2022-06-20后创建的自建应用在无固定IP时需要（企微要求登记可信IP）。不使用代理请保留默认值 https://qyapi.weixin.qq.com'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.wecom.proxy');
