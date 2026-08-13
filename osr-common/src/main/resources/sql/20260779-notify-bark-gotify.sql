-- ----------------------------
-- 20260779: 新增 Bark / Gotify 两个通知渠道的配置项
--
-- 两者都是「一个地址 + 一段文本」，用来检验 20260778 那套路由抽象是不是真的
-- 「加一个实现类就多一列」。渠道的开关与收件人不在这里配，去「通知路由」页。
--
-- 不为新渠道插 notify_route 行：路由缺失按「发送」处理（见 NotifyRouteService#find），
-- 所以用户配好地址就能立刻收到；打开配置页保存一次即可落成显式行。
-- 反过来预先插行会让「用户还没配地址、路由却已存在」，页面上显得像已经在用了。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'Bark 推送地址', 'openlist.notify.bark.url', '', 'N', 'admin', '2026-08-12 00:00:00',
       'Bark 推送地址，形如 https://api.day.app/你的Key（自建服务填自己的域名）。留空则不启用'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.bark.url');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'Gotify 服务地址', 'openlist.notify.gotify.url', '', 'N', 'admin', '2026-08-12 00:00:00',
       'Gotify 服务地址，形如 https://gotify.example.com（不含路径）。留空则不启用'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.gotify.url');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'Gotify 应用 Token', 'openlist.notify.gotify.token', '', 'N', 'admin', '2026-08-12 00:00:00',
       'Gotify 的 application token，在 Gotify 后台创建应用后获得。与服务地址都填了才会发送'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.gotify.token');
