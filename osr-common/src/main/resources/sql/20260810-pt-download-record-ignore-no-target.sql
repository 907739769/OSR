-- ----------------------------
-- 20260810: 「无目标集」失败记录补成已忽略（幂等脚本）
--
-- 包内不含任何目标集的种子会被中止删除、相关集退回重新匹配，没有需要人处理的事，
-- 自此版本起落库时即置 fail_ignored。这里把存量记录补齐，免得它们继续挂在首页「下载失败待处理」里。
-- 统计仪表盘不看 fail_ignored，失败数与成功率不受影响。
-- ----------------------------

UPDATE `pt_download_record` SET `fail_ignored` = '1'
WHERE `state` = 'FAILED' AND `fail_reason_code` = 'NO_TARGET_EPISODE' AND `fail_ignored` <> '1';
