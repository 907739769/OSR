-- ----------------------------
-- 20260718: 刮削(NFO+图片)增加"是否强制覆盖已有文件"配置，此前该行为硬编码为不覆盖
-- ----------------------------

ALTER TABLE `rename_task`
  ADD COLUMN `scrape_force_overwrite` char(1) NULL DEFAULT '0' COMMENT '刮削时是否强制覆盖已有NFO/图片 0-否 1-是' AFTER `scrape_images`;
