-- ----------------------------
-- 20260782: pt_filter_config 新增 description_exclude_keywords 列
--
-- 背景：exclude_keywords 只拿种子标题去匹配，而有一类关键属性标题里根本不写——最典型的是
-- 蓝光原盘：国内 PT 站普遍只在种子描述里标一句「原盘」，标题跟压制版长得一模一样
-- （两者都解析成 source=BluRay，来源白名单也分不开）。此前唯一能拦原盘的手段是体积上限，
-- 但那会把体积区间重叠的 REMUX 一并切掉，而 REMUX 是 mkv、播放器本来吃得下。
--
-- 语义与 exclude_keywords 完全一致（逗号分隔、命中任一即淘汰、大小写不敏感），只是判定对象
-- 换成 description。默认空串即不启用，既有用户行为不变。
--
-- 注意：描述为空时一律放行而非淘汰。不少索引器压根不返回 <description>，
-- 按「判不出就淘汰」处理会把整站候选清光。
-- ----------------------------
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'description_exclude_keywords');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `description_exclude_keywords` varchar(500) NOT NULL DEFAULT '''' COMMENT ''逗号分隔，种子描述命中任一则淘汰。用于拦截标题看不出、只在描述里标注的属性（如蓝光原盘）'' AFTER `exclude_keywords`', 'SELECT ''Column description_exclude_keywords already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
