-- ----------------------------
-- 20260791: 菜单图标换成 lucide 名，顺带把搭配错的、重复的一次改对
--
-- 前端图标从 @mdi/font 换成了 lucide（按需引入的 SVG，替掉整包 403KB 的 webfont），
-- `sys_menu.icon` 因此要换成 lucide 的官方名。与 20260780 同一个思路：库里存什么、
-- 模板里写什么，都是图标集的原名，**中间不留翻译字典**。字典那条路走过四次
-- （sql/ 下 4 个 fix-menu-icon 迁移），每次都是「建菜单要改两处、漏了不报错、
-- 只是那个菜单没图标」。
--
-- 顺带修掉两类历史问题，它们都是 20260780 机械批量翻译留下的：
--
--   1. **错译**。最典型的是「参数设置」(106)：建库时写的是 `fa fa-picture-o`（图片），
--      字典忠实地翻成 `mdi-image-outline`，于是「参数设置」旁边挂着一张风景照。
--      同类的还有「网盘同步」(2067) 的纸飞机、「同步任务记录」(2013) 的刷新箭头、
--      「定时任务」(110) 的扳手。
--
--   2. **撞车**。字典是多对一的（`fa fa-list` 和 `fa fa-list-ul` 都翻成
--      `mdi-format-list-bulleted`），加上后来新建的菜单各自取名，库里出现了
--      三个菜单共用 `mdi-play-circle-outline`（同步任务配置 / strm任务配置 /
--      STRM生成记录）、两组共用 `mdi-wrench-outline` 与
--      `mdi-file-document-multiple-outline` 的情况。侧边栏里两行图标一模一样，
--      比图标不贴切更影响辨认——用户扫的是图标，不是逐字读菜单名。
--
-- 改后 27 个二级菜单的图标两两不同，9 个一级分组也各不相同。
--
-- 逐条按 `menu_id` 改，不按 icon 旧值批量改：旧值本来就有一对多的情况，
-- 按值 UPDATE 必然误伤（20260786 已经踩过一次）。
--
-- 一级分组（menu_type='M'）的图标目前前端并不渲染（`SidebarMenuItem.vue` 把分组
-- 画成一行纯标题，只有叶子节点带图标），这里仍然一并改：留着 mdi- 名等于让同一列
-- 同时存在两套取值，下次谁想给分组加图标时会直接踩空。
--
-- 「企业微信用户」(2075) 用 `brand-wecom`——lucide 官方不收品牌 logo，这个名字对应
-- 前端内联的 simple-icons 路径（见 plugins/lucideIcons.ts）。
-- ----------------------------

-- 一级分组（当前不渲染，保持取值口径一致）
UPDATE `sys_menu` SET `icon` = 'settings'            WHERE `menu_id` = 1;     -- 系统管理
UPDATE `sys_menu` SET `icon` = 'activity'            WHERE `menu_id` = 2;     -- 系统监控（原 mdi-monitor，与 STRM生成 撞车）
UPDATE `sys_menu` SET `icon` = 'folder-sync'         WHERE `menu_id` = 2067;  -- 网盘同步（原纸飞机 send）
UPDATE `sys_menu` SET `icon` = 'film'                WHERE `menu_id` = 2068;  -- STRM生成（原 mdi-monitor）
UPDATE `sys_menu` SET `icon` = 'wand-sparkles'       WHERE `menu_id` = 2069;  -- 智能重命名（原文档堆，与实时日志 撞车）
UPDATE `sys_menu` SET `icon` = 'tv'                  WHERE `menu_id` = 2070;  -- PT 追剧
UPDATE `sys_menu` SET `icon` = 'cloud-download'      WHERE `menu_id` = 2079;  -- PT 下载
UPDATE `sys_menu` SET `icon` = 'sliders-horizontal'  WHERE `menu_id` = 2080;  -- PT 规则
UPDATE `sys_menu` SET `icon` = 'server'              WHERE `menu_id` = 2081;  -- PT 接入

-- 系统管理
UPDATE `sys_menu` SET `icon` = 'book-open'           WHERE `menu_id` = 105;   -- 字典管理（与页面 PageHeader 统一）
UPDATE `sys_menu` SET `icon` = 'settings-2'          WHERE `menu_id` = 106;   -- 参数设置（原 mdi-image-outline，一张风景照）
UPDATE `sys_menu` SET `icon` = 'brand-wecom'         WHERE `menu_id` = 2075;  -- 企业微信用户
UPDATE `sys_menu` SET `icon` = 'bell-ring'           WHERE `menu_id` = 2077;  -- 通知路由

-- 系统监控
UPDATE `sys_menu` SET `icon` = 'timer'               WHERE `menu_id` = 110;   -- 定时任务（原扳手，与一致性检查 撞车）
UPDATE `sys_menu` SET `icon` = 'scroll-text'         WHERE `menu_id` = 5000;  -- 实时日志（原文档堆）

-- 网盘同步
UPDATE `sys_menu` SET `icon` = 'folder-cog'          WHERE `menu_id` = 2025;  -- 同步任务配置（原播放键，三处撞车之一）
UPDATE `sys_menu` SET `icon` = 'history'             WHERE `menu_id` = 2013;  -- 同步任务记录（原刷新箭头）

-- STRM 生成
UPDATE `sys_menu` SET `icon` = 'file-cog'            WHERE `menu_id` = 2037;  -- strm任务配置（原播放键，三处撞车之一）
UPDATE `sys_menu` SET `icon` = 'clapperboard'        WHERE `menu_id` = 2019;  -- STRM生成记录（原播放键，三处撞车之一）

-- 智能重命名
UPDATE `sys_menu` SET `icon` = 'text-cursor-input'   WHERE `menu_id` = 2049;  -- 重命名任务配置
UPDATE `sys_menu` SET `icon` = 'replace'             WHERE `menu_id` = 2060;  -- 重命名规则设置
UPDATE `sys_menu` SET `icon` = 'list'                WHERE `menu_id` = 2043;  -- 重命名明细
UPDATE `sys_menu` SET `icon` = 'file-x'              WHERE `menu_id` = 2055;  -- 重命名一致性检查（原扳手，与定时任务 撞车）

-- PT 追剧
UPDATE `sys_menu` SET `icon` = 'calendar-days'       WHERE `menu_id` = 2076;  -- 追剧日历
UPDATE `sys_menu` SET `icon` = 'stethoscope'         WHERE `menu_id` = 2082;  -- 缺集体检
UPDATE `sys_menu` SET `icon` = 'bookmark'            WHERE `menu_id` = 2064;  -- 订阅管理
UPDATE `sys_menu` SET `icon` = 'flame'               WHERE `menu_id` = 2073;  -- 热门自动订阅（星星 → 火，「热门」更直白）

-- PT 下载
UPDATE `sys_menu` SET `icon` = 'clipboard-list'      WHERE `menu_id` = 2066;  -- 下载记录（原项目符号列表，与重命名明细 撞车）
UPDATE `sys_menu` SET `icon` = 'chart-column'        WHERE `menu_id` = 2071;  -- 统计仪表盘
UPDATE `sys_menu` SET `icon` = 'arrow-left-right'    WHERE `menu_id` = 2078;  -- 转移做种

-- PT 规则
UPDATE `sys_menu` SET `icon` = 'funnel'              WHERE `menu_id` = 2065;  -- 过滤规则
UPDATE `sys_menu` SET `icon` = 'circle-arrow-up'     WHERE `menu_id` = 2074;  -- 洗版规则
UPDATE `sys_menu` SET `icon` = 'ban'                 WHERE `menu_id` = 2072;  -- 黑名单（叉号 → 禁止号）

-- PT 接入
UPDATE `sys_menu` SET `icon` = 'scan-search'         WHERE `menu_id` = 2061;  -- 索引器（与页面 PageHeader 统一）
UPDATE `sys_menu` SET `icon` = 'download'            WHERE `menu_id` = 2062;  -- 下载器
UPDATE `sys_menu` SET `icon` = 'monitor-play'        WHERE `menu_id` = 2063;  -- 媒体服务器（原胶片，与 STRM生成 分组同款）

-- 兜底：上面按 menu_id 逐条改的是当前库里已知的全部 M/C 菜单。若某个库里还有别的
-- 菜单行（手工加过、或将来新增后忘了跟进），它的 icon 仍是 mdi-*/fa-*，前端会退化成
-- 通用图标（getIconComponent），不会白掉一块，但也提示这行需要人工补一个 lucide 名。
