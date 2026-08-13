-- ----------------------------
-- 20260781: 删除已被通知路由表取代的三个类型过滤配置
--
-- 20260778 把「哪个类型走哪个渠道」搬进了 notify_route 表，代码已不再读
-- openlist.notify.{tg,webhook,wecom}.types。但配置行还留在 sys_config 里，
-- 用户在「参数设置」页仍然看得到、改得动，改完却毫无效果——这种"还在那儿但已失效"
-- 的开关比没有更让人困惑。
--
-- 值本身已在 20260778 里被逐类型展开成 notify_route 的行，删除不丢信息。
-- ----------------------------

DELETE FROM `sys_config` WHERE `config_key` IN (
    'openlist.notify.tg.types',
    'openlist.notify.webhook.types',
    'openlist.notify.wecom.types'
);
