-- ----------------------------
-- 20260711: 增加刮削(NFO+图片)功能字段
-- ----------------------------

-- rename_task: 增加刮削开关配置
ALTER TABLE `rename_task`
  ADD COLUMN `scrape_enabled` char(1) NULL DEFAULT '0' COMMENT '是否启用刮削 0-否 1-是' AFTER `status`,
  ADD COLUMN `scrape_nfo` char(1) NULL DEFAULT '0' COMMENT '是否生成NFO 0-否 1-是' AFTER `scrape_enabled`,
  ADD COLUMN `scrape_images` char(1) NULL DEFAULT '0' COMMENT '是否下载图片 0-否 1-是' AFTER `scrape_nfo`;

-- rename_detail: 增加刮削状态记录
ALTER TABLE `rename_detail`
  ADD COLUMN `scrape_status` char(1) NULL DEFAULT '0' COMMENT '刮削状态 0-未执行 1-成功 2-失败' AFTER `status`,
  ADD COLUMN `scrape_msg` varchar(500) NULL DEFAULT NULL COMMENT '刮削失败原因' AFTER `scrape_status`;
