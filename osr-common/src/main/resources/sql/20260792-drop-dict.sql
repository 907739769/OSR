-- ----------------------------
-- 20260792: 下线字典管理，扩展名清单迁入参数设置
--
-- 背景：sys_dict 经 20260510 / 20260511 两次清理后只剩 2 个类型 13 条数据
-- （openlist_video_type / openlist_srt_type），唯一消费者是 OpenListHelper#isVideo/isSrt。
-- 一个带完整 CRUD、两级页面、5 个权限点、一级菜单入口的通用配置框架，实际只承担
-- 两份扩展名清单——与本项目「影视 STRM 管理」的定位不符，且带着两个实际问题：
--   1. 改了不生效：SysDictDataHelper 的缓存靠 refreshCache(String)，而那个方法全项目
--      零调用方。用户在页面上加一个扩展名，保存成功、列表刷新，业务侧却仍用旧集合，
--      非重启后端不生效，且没有任何错误现象。
--   2. 两个 SysDict*ApiController 都没有 adminOnlyWrite，任何登录用户都能改全站扩展名。
-- 迁到 sys_config 后，OpenlistConfig 每次 selectConfigByKey 取值、updateConfig 写入时
-- 刷新缓存，生效路径本身是通的；MediaExtensionProvider 按配置原文缓存，
-- 结构上不存在「忘记让缓存失效」这回事。
--
-- 注意：schema.sql 的建表语句与 init.sql 的 13 条种子数据刻意保留
-- （与 sys_notice 被 20260510 DROP 但 schema.sql 仍建表的处理一致）。
-- 下面第 1 步要从字典表里读出扩展名来拼配置值，表必须存在：
--   - 全新安装：schema 建表 → init 插入 13 条 → 本脚本读出来拼成配置 → DROP
--   - 存量升级：读出用户自己维护过的那份 → DROP
-- 两条路径共用同一份默认值定义（init.sql），不会漂移。反过来把 init.sql 的 INSERT
-- 删掉的话，全新安装会在这里读到空表、拼出两条空配置。
-- ----------------------------

-- 1. 扩展名清单迁入 sys_config。GROUP_CONCAT 为 NULL（该字典类型已被清空）时退回
--    内置默认值，与 OpenlistConfig 里的兜底常量逐字一致——空配置的语义是
--    「没有任何文件是视频」，同步与 STRM 生成会安静地一个文件都不处理。
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '视频扩展名', 'openlist.strm.video.extensions',
       COALESCE((SELECT GROUP_CONCAT(`dict_value` ORDER BY `dict_sort` SEPARATOR ',')
                 FROM `sys_dict_data` WHERE `dict_type` = 'openlist_video_type'),
                'mp4,mkv,avi,mov,rmvb,flv,webm,m3u8,wmv,iso,ts'),
       'N', 'admin', '2026-08-22 00:00:00',
       '判定「哪些文件要生成 STRM」的扩展名清单，逗号分隔、不带点。留空会导致所有文件都不被识别为视频'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.strm.video.extensions');

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '字幕扩展名', 'openlist.strm.subtitle.extensions',
       COALESCE((SELECT GROUP_CONCAT(`dict_value` ORDER BY `dict_sort` SEPARATOR ',')
                 FROM `sys_dict_data` WHERE `dict_type` = 'openlist_srt_type'),
                'ass,srt'),
       'N', 'admin', '2026-08-22 00:00:00',
       '判定「哪些文件是字幕」的扩展名清单，逗号分隔、不带点。仅在开启「STRM 下载字幕」时生效'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.strm.subtitle.extensions');

-- 2. 删除字典管理菜单（1 个 C 菜单 + 5 个 F 权限点）及其角色授权。
--    按 perms / url 匹配而不是写死 menu_id：id 在存量库里可能被改过，
--    而这两个字段是功能本身的标识。派生表包一层是 MySQL 的要求
--    （DELETE 的子查询不能直接引用正在删的那张表）。
DELETE FROM `sys_role_menu` WHERE `menu_id` IN (
    SELECT `menu_id` FROM (
        SELECT `menu_id` FROM `sys_menu`
        WHERE `perms` LIKE 'system:dict:%' OR `url` LIKE '/system/dict%'
    ) t
);
DELETE FROM `sys_menu` WHERE `perms` LIKE 'system:dict:%' OR `url` LIKE '/system/dict%';

-- 3. 删表。放在最后：上面第 1 步还要从中读数据。
DROP TABLE IF EXISTS `sys_dict_data`;
DROP TABLE IF EXISTS `sys_dict_type`;
