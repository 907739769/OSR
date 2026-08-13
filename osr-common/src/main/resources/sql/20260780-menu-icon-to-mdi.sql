-- ----------------------------
-- 20260780: 菜单图标直接存 mdi 名，拆掉 FA→MDI 的翻译层
--
-- 为什么以前要翻译：sys_menu.icon 存的是 Font Awesome 类名（从 RuoYi 继承），
-- 而本项目前端是 Vuetify，用 MDI 图标，且**根本没有引入 Font Awesome**——
-- 那些 fa fa-* 在前端是一个谁也不认识的死词汇，只能靠 useMenuIcon.ts 里一张
-- 手写字典翻译成 mdi 名。
--
-- 代价是新建菜单要改两个地方（SQL 一处、前端字典一处），漏了第二处既不报错也无告警，
-- 只有等人发现「这个菜单怎么没图标」。sql/ 目录下已经有 4 个 fix-menu-icon 迁移，
-- 20260778 的「通知路由」又栽了一次。而本项目没有菜单管理页面，菜单只能靠开发者写
-- SQL 建——这张字典唯一的服务对象就是开发者自己。
--
-- 因此直接存 mdi 名，前端原样透传。以后建菜单只改一处，图标名写错当场就能看见。
-- 下面按 useMenuIcon.ts 原字典逐条替换，字典本身就是标准答案，机械替换无损。
-- 前端仍保留那张字典作兜底，升级路径上漏网的 fa fa-* 仍能被翻译。
-- ----------------------------

UPDATE `sys_menu` SET `icon` = 'mdi-cog' WHERE `icon` = 'fa fa-gear';
UPDATE `sys_menu` SET `icon` = 'mdi-cog' WHERE `icon` = 'fa fa-cog';
UPDATE `sys_menu` SET `icon` = 'mdi-file-document-outline' WHERE `icon` = 'fa fa-bookmark-o';
UPDATE `sys_menu` SET `icon` = 'mdi-image-outline' WHERE `icon` = 'fa fa-sun-o';
UPDATE `sys_menu` SET `icon` = 'mdi-monitor' WHERE `icon` = 'fa fa-video-camera';
UPDATE `sys_menu` SET `icon` = 'mdi-wrench-outline' WHERE `icon` = 'fa fa-tasks';
UPDATE `sys_menu` SET `icon` = 'mdi-calendar' WHERE `icon` = 'fa fa-calendar';
UPDATE `sys_menu` SET `icon` = 'mdi-image-outline' WHERE `icon` = 'fa fa-picture-o';
UPDATE `sys_menu` SET `icon` = 'mdi-coin' WHERE `icon` = 'fa fa-yen';
UPDATE `sys_menu` SET `icon` = 'mdi-send-outline' WHERE `icon` = 'fa fa-send-o';
UPDATE `sys_menu` SET `icon` = 'mdi-diamond-stone' WHERE `icon` = 'fa fa-diamond';
UPDATE `sys_menu` SET `icon` = 'mdi-menu' WHERE `icon` = 'fa fa-bars';
UPDATE `sys_menu` SET `icon` = 'mdi-format-list-bulleted' WHERE `icon` = 'fa fa-list-ul';
UPDATE `sys_menu` SET `icon` = 'mdi-format-list-bulleted' WHERE `icon` = 'fa fa-list';
UPDATE `sys_menu` SET `icon` = 'mdi-file-document-multiple-outline' WHERE `icon` = 'fa fa-file-code-o';
UPDATE `sys_menu` SET `icon` = 'mdi-folder-open-outline' WHERE `icon` = 'fa fa-folder-open-o';
UPDATE `sys_menu` SET `icon` = 'mdi-play-circle-outline' WHERE `icon` = 'fa fa-play-circle-o';
UPDATE `sys_menu` SET `icon` = 'mdi-play-circle-outline' WHERE `icon` = 'fa fa-video-play';
UPDATE `sys_menu` SET `icon` = 'mdi-refresh' WHERE `icon` = 'fa fa-copy';
UPDATE `sys_menu` SET `icon` = 'mdi-pencil-outline' WHERE `icon` = 'fa fa-edit';
UPDATE `sys_menu` SET `icon` = 'mdi-auto-fix' WHERE `icon` = 'fa fa-magic';
UPDATE `sys_menu` SET `icon` = 'mdi-rss' WHERE `icon` = 'fa fa-rss';
UPDATE `sys_menu` SET `icon` = 'mdi-download-outline' WHERE `icon` = 'fa fa-download';
UPDATE `sys_menu` SET `icon` = 'mdi-filmstrip' WHERE `icon` = 'fa fa-server';
UPDATE `sys_menu` SET `icon` = 'mdi-filter-outline' WHERE `icon` = 'fa fa-sliders';
UPDATE `sys_menu` SET `icon` = 'mdi-chart-line' WHERE `icon` = 'fa fa-bar-chart';
UPDATE `sys_menu` SET `icon` = 'mdi-close-circle-outline' WHERE `icon` = 'fa fa-ban';
UPDATE `sys_menu` SET `icon` = 'mdi-star' WHERE `icon` = 'fa fa-fire';
UPDATE `sys_menu` SET `icon` = 'mdi-arrow-up-bold-circle-outline' WHERE `icon` = 'fa fa-arrow-circle-o-up';
UPDATE `sys_menu` SET `icon` = 'mdi-heart-outline' WHERE `icon` = 'fa fa-heart';
UPDATE `sys_menu` SET `icon` = 'mdi-wechat' WHERE `icon` = 'fa fa-weixin';
UPDATE `sys_menu` SET `icon` = 'mdi-bell-outline' WHERE `icon` = 'fa fa-bell-o';
-- 20260778 建的「通知路由」用了 fa fa-bell-o，字典里没有它，所以一直没图标
UPDATE `sys_menu` SET `icon` = 'mdi-bell-outline' WHERE `icon` = 'fa fa-bell-o';
