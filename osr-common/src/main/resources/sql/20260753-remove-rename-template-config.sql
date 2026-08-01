-- ----------------------------
-- 20260753: 移除 sys_config 中的重命名文件名模板参数（rename.filename.template）
-- 该参数已由独立的"重命名规则设置"页面（/openlist/renameConfig）通过
-- /openliststrm/rename-config/template 接口读写管理，参数设置页里再展示一个
-- 549 字符的 Pebble 模板串既不合适也是重复入口。
-- 删除后 IRenameTemplateConfigService.getTemplate() 会 fallback 到内置 DEFAULT_TEMPLATE；
-- 若此前已在"重命名规则设置"页保存过自定义模板，请先确认无需保留。
-- 注意：本脚本由 MysqlDdl 在启动时执行（无缓存问题）。若手动对运行中的库执行，
-- sys_config 内存缓存不会自动失效，需重启后端或调用"刷新缓存"后再生效。
-- DELETE 本身幂等，重复执行安全。
-- ----------------------------

DELETE FROM `sys_config` WHERE `config_key` = 'rename.filename.template';
