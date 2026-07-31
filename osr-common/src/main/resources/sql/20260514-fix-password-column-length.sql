-- 修复密码列长度不足问题
-- BCrypt 哈希输出为 60 字符（如 $2a$10$...），原 varchar(50) 会导致 Data too long 错误
-- 扩展为 varchar(128) 以兼容 BCrypt 及未来更强哈希算法

ALTER TABLE `sys_user` MODIFY COLUMN `password` varchar(128)  NULL DEFAULT '' COMMENT '密码';
