-- 订阅级洗版开关默认改为关闭。
-- 洗版会额外消耗索引器配额与下载器带宽，且新旧版本会同时留在库里（OSR 从不删种），
-- 属于用户明确想要才该开的行为，不该是建订阅时的默认值。
-- 只改列默认值，不动已有行：已经建好的订阅保持用户当前的选择。
ALTER TABLE `pt_subscription` ALTER COLUMN `upgrade_enabled` SET DEFAULT '0';
