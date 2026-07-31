-- 清理未使用的数据库表
-- gen_table / gen_table_column: RuoYi 代码生成器表，项目已不使用
-- sys_notice: 通知公告表，项目已不使用
--
-- 清理未使用的字典类型及数据（共 15 个字典类型，107 条字典数据）
-- 未使用原因：
--   - RuoYi 内置字典（10个）：前端和后端均无引用
--   - openlist_copy_status / openlist_strm_status / openlist_copy_task_status：已有对应 Java 枚举

-- 删除未使用的字典数据（先删数据，再删类型）
DELETE FROM `sys_dict_data` WHERE `dict_type` IN (
    'sys_user_sex', 'sys_show_hide', 'sys_normal_disable',
    'sys_job_status', 'sys_job_group', 'sys_yes_no',
    'sys_notice_type', 'sys_notice_status', 'sys_oper_type',
    'sys_common_status',
    'openlist_copy_status', 'openlist_strm_status', 'openlist_copy_task_status'
);

-- 删除未使用的字典类型
DELETE FROM `sys_dict_type` WHERE `dict_type` IN (
    'sys_user_sex', 'sys_show_hide', 'sys_normal_disable',
    'sys_job_status', 'sys_job_group', 'sys_yes_no',
    'sys_notice_type', 'sys_notice_status', 'sys_oper_type',
    'sys_common_status',
    'openlist_copy_status', 'openlist_strm_status', 'openlist_copy_task_status'
);

DROP TABLE IF EXISTS `gen_table_column`;
DROP TABLE IF EXISTS `gen_table`;
DROP TABLE IF EXISTS `sys_notice`;
