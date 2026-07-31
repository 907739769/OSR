-- 清理未使用的字典类型及数据
-- RuoYi 内置字典（10个）：前端和后端均无引用
-- 业务冗余字典（3个）：已有对应 Java 枚举
-- 重命名相关字典（3个）：从未使用

DELETE FROM `sys_dict_data` WHERE `dict_type` IN (
    'sys_user_sex', 'sys_show_hide', 'sys_normal_disable',
    'sys_job_status', 'sys_job_group', 'sys_yes_no',
    'sys_notice_type', 'sys_notice_status', 'sys_oper_type',
    'sys_common_status',
    'openlist_copy_status', 'openlist_strm_status', 'openlist_copy_task_status',
    'rename_resolution', 'rename_status', 'media_type'
);
DELETE FROM `sys_dict_type` WHERE `dict_type` IN (
    'sys_user_sex', 'sys_show_hide', 'sys_normal_disable',
    'sys_job_status', 'sys_job_group', 'sys_yes_no',
    'sys_notice_type', 'sys_notice_status', 'sys_oper_type',
    'sys_common_status',
    'openlist_copy_status', 'openlist_strm_status', 'openlist_copy_task_status',
    'rename_resolution', 'rename_status', 'media_type'
);
