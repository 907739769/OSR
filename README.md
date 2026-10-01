<div align="center">

# OSR · OpenList STRM Relay

**网盘党的全自动影视库：PT 追剧 → 传网盘 → 生成 STRM → 刮削入库，一套系统走完。**

订阅没下下来？OSR 会告诉你**为什么**。

[![GitHub Stars](https://img.shields.io/github/stars/907739769/OSR?style=flat&logo=github)](https://github.com/907739769/OSR/stargazers)
[![Docker Pulls](https://img.shields.io/docker/pulls/jacksaoding/osr-backend?logo=docker)](https://hub.docker.com/r/jacksaoding/osr-backend)
[![Release](https://img.shields.io/github/v/release/907739769/OSR)](https://github.com/907739769/OSR/releases)
[![License](https://img.shields.io/github/license/907739769/OSR)](LICENSE)

[快速开始](#-快速开始) · [上手教程](https://blog.jackding.cn/archives/osr-shang-shou-zhi-nan-yong-yi-tao-xi-tong-ba-pt-xia-zai-wang-pan-tong-bu-strm-sheng-cheng-he-gua-xue-chong-ming-ming-chuan-qi-lai) · [Wiki](https://github.com/907739769/OSR/wiki) · [完整功能](#完整功能) · [截图](#演示图)

<img src="https://github.com/user-attachments/assets/b349904a-054e-48f4-9133-fe77f0dac5f8" width="90%"/>

</div>

## ✨ 为什么用 OSR

**☁️ 为网盘 + STRM 而生，不靠插件拼凑**
OpenList 挂载的网盘是一等公民：本地 ↔ 网盘文件夹同步、STRM 生成（支持增量扫描）、TMDb 重命名刮削、孤儿文件扫描。qBittorrent 下载完成后，自动完成「同步 → 传网盘 → 生成 STRM」整条链路。

**🔍 订阅卡住时能看到原因，不是一个黑盒**
- **一键诊断**：逐集列出最近一轮搜到多少候选、每个候选因为什么被淘汰
- **过滤规则回放**：改规则之前，先用真实候选看看会多放过、多挡掉哪些种子
- **缺集体检 / 字幕体检**：播出多日还没入库的集会被列出来并附上原因，没有中字的集也会标出来

**📱 远程管理：手机、TG、企微、AI 助手都能用**
PWA 移动端；Telegram / 企业微信的通知自带「重试」「拉黑」「补搜」按钮；内置 MCP 服务端，Claude、Cursor 等 AI 助手可以直接查追剧进度、触发任务。

## 🚀 快速开始

需要 Docker 20.10+ 和 Docker Compose v2。

**1. 新建目录，下载 compose 文件和环境变量模板**

```bash
mkdir osr && cd osr
curl -fsSL -o docker-compose.yml https://raw.githubusercontent.com/907739769/OSR/master/docker-compose.image.yml
curl -fsSL -o .env https://raw.githubusercontent.com/907739769/OSR/master/.env.example
```

> 下载不了的话，也可以直接在仓库里打开 [docker-compose.image.yml](docker-compose.image.yml) 和 [.env.example](.env.example)，复制内容保存为 `docker-compose.yml` 和 `.env`。

**2. 编辑 `.env`**，至少把这四项的占位值换成你自己的随机字符串：

```ini
MYSQL_ROOT_PASSWORD=改成随机字符串
DB_PASSWORD=改成随机字符串
JWT_SECRET=改成至少32位的随机字符串
PT_CREDENTIAL_SECRET=改成至少32位的随机字符串
```

**3. 启动**

```bash
docker compose up -d
```

首次启动要初始化数据库，大约需要 1～3 分钟。`docker compose ps` 里 backend 显示 `(healthy)` 后，浏览器打开 `http://<你的IP>`，用 `admin` 登录（默认密码见[上手教程](https://blog.jackding.cn/archives/osr-shang-shou-zhi-nan-yong-yi-tao-xi-tong-ba-pt-xia-zai-wang-pan-tong-bu-strm-sheng-cheng-he-gua-xue-chong-ming-ming-chuan-qi-lai)），**登录后请立即修改密码**。

> 起不来？Java 异常写在容器内的 `/data/logs/sys-error.log`，不在 `docker logs` 里。更多排查方法见[上手教程](https://blog.jackding.cn/archives/osr-shang-shou-zhi-nan-yong-yi-tao-xi-tong-ba-pt-xia-zai-wang-pan-tong-bu-strm-sheng-cheng-he-gua-xue-chong-ming-ming-chuan-qi-lai)。

## 🤔 和 MoviePilot 怎么选

两者都能做 PT 自动追剧，侧重点不同：

| | OSR | MoviePilot |
|---|---|---|
| 设计重心 | 网盘 + STRM 工作流 | PT 自动化中枢 |
| STRM 生成 | 内置（增量扫描、孤儿文件扫描） | 主要依靠社区插件 |
| 订阅排障 | 逐集淘汰原因、规则回放、缺集 / 字幕体检 | 以查看日志为主 |
| 站点接入 | 通过 Jackett / Prowlarr（Torznab） | 内置大量站点适配 ✅ |
| 扩展能力 | 无插件机制 | 插件生态丰富 ✅ |
| 部署 | 3 个容器（含 MySQL） | 单容器 ✅ |

**如果你的媒体库放在网盘、用 STRM 播放，或者经常搞不清「这集为什么没下」，OSR 会更顺手；如果你是纯本地硬盘，并且重度依赖插件生态，MoviePilot 更合适。** 两者也可以共存：用 MoviePilot 下载，用 OSR 处理网盘同步和 STRM。

## 完整功能

### 🎬 STRM 文件生成
- 支持定时任务自动执行、前端页面手动触发、Telegram Bot 指令执行
- 递归扫描网盘目录，为视频文件生成对应的 STRM 流媒体文件
- 支持多任务并行配置与批量执行，输出目录 / 字幕 / 最小体积可按任务单独覆盖
- **增量扫描**（按任务开启）：定时执行跳过修改时间没变的叶子目录，默认每 7 天全量兜底一次

### 📂 文件夹同步
- 同步 OpenList 两个文件夹之间的文件（本地 ↔ 云盘）
- 支持定时任务、Telegram Bot、REST API 三种触发方式
- 支持单文件增量同步与全量同步
- 记录页带状态统计条、「重试全部失败」、详情抽屉，显示文件大小与失败原因

### 🔄 影视文件重命名
- 基于 TMDB 元数据自动识别并重命名电影/剧集文件，可选 AI 兜底识别
- 分类规则可视化配置：标出永远命不中的规则、模板预览同时给电影与剧集样例、测试给出目标目录
- 孤儿文件扫描与一致性检查，支持清理与忽略规则

### 📥 PT 订阅（全自动追剧）
- 指定一部剧或电影，自动从 PT 站找资源 → 推下载器 → 追踪下载 → 确认入库，缺哪集补哪集
- Torznab 索引器（Jackett / Prowlarr）+ 下载器（qBittorrent / Transmission）+ 媒体服务器（**Emby / Jellyfin / Plex**，多台同时参与入库判定）
- 过滤与择优、季包按目标集筛文件（只缺一两集时优先单集）、洗版、H&R 追踪、自动删种、转移做种
- **追剧日历 / 缺集体检 / 字幕体检**：按播出日期铺开每一集，列出播出多日仍未入库的集与原因；按媒体服务器的实际字幕流标出没有中字的集
- **订阅一键诊断**：逐集列出最近一轮搜索的候选数和按原因计数的淘汰情况
- **过滤规则历史回放**：用最近的真实候选对比新旧规则，改之前就知道会多放过/多挡掉哪些种子
- **资源搜索**：不建订阅，按关键词直接搜全部站点；标出全局过滤规则会挡掉哪些（回答「为什么订了却没自动下」），可直接下载（管理员）或一键转为订阅
- **热门自动订阅**：TMDb 榜单、RSSHub 豆瓣榜单，以及「跟一个电影系列 / 导演演员」自动订阅其电影
- **观看状态同步**：订阅卡片显示已看集数，自动补搜优先补正在看的剧
- **入库后通知媒体库刷新**（按服务器开启）：STRM 生成、重命名、刮削写完文件后，或清理产物删掉文件后，通知 Emby / Jellyfin / Plex 只扫描对应目录，不用等它的定时扫描，随后提前对账一轮、订阅几分钟内就显示已入库；支持路径映射，配置页可逐条检查映射是否落在媒体库内
- **统计仪表盘**：下载趋势、索引器命中率、淘汰/失败原因，以及保种体积与每日上传量看板
- 多用户：订阅按归属隔离，订阅页与日历可按归属筛选

### 🔔 通知与远程控制
- 五个通知渠道：Telegram Bot、企业微信自建应用、通用 Webhook、Bark、Gotify；由通知路由表决定「发不发、发给谁」
- 通知带快捷操作：下载失败一键重试/拉黑，补搜落空、缺集逾期一键补搜（TG 为内联按钮）
- Telegram 与企业微信共用同一套订阅指令：订阅、进度、补搜、诊断、暂停/恢复、重试下载、拉黑种子
- 每周周报（可选）：汇总 7 天同步/STRM/重命名/PT 情况，配了 OpenAI 时附一段点评

### 🤖 AI 与 MCP
- **MCP 服务端**：端点 `/mcp`，本地 AI 助理（Claude Desktop、Cursor 等）凭令牌查订阅与追剧进度、触发任务
- 自然语言建过滤规则：一句话描述，AI 生成草稿填进表单，确认后才保存
- 种子标题 AI 兜底解析：本地解析不出的标题后台交给 AI，结果缓存（默认关）

### 🔗 第三方回调自动化
- 开放 API 接收第三方应用回调通知（如 qBittorrent 下载完成）
- 自动触发文件同步 → 云盘复制 → STRM 生成的完整工作流
- APIKEY 鉴权保障接口安全

### 📊 监控与运维
- **首页待办**：失败记录、下载器离线、媒体服务器连不上、失败下载待处理，点一下直达
- **实时日志**：WebSocket 推送，支持暂停、traceId 过滤、导出；「检索历史」可在全部日志文件（含滚动分片）里按关键字/级别/时间检索
- **定时任务**：手动执行转后台不再超时，列表显示下次/上次执行时间，执行记录带开始/结束时间
- **配置备份与恢复**：各项配置与 PT 订阅一键导出为 JSON，恢复前逐项预览变化，按名称合并、从不删除；密码/API Key 默认不导出

### 🔐 系统管理
- 用户管理、角色管理、菜单管理、参数设置
- JWT 无状态认证，接口级权限控制（索引器/下载器等写操作仅管理员），登录失败按账号/IP 双桶锁定
- PC 与移动端（H5/PWA）两套界面，移动端为悬浮底栏 + 底部「更多」面板

## 技术栈

| 类别 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.0.6 (Java 25, Preview Features) |
| 前端框架 | Vue 3 + Vite + Pinia + Vuetify 3 + PWA |
| 认证授权 | JWT |
| 数据访问 | MyBatis-Plus 3.5.7 + MySQL 8.0 + Druid |
| JSON | FastJSON2 |
| 消息通知 | Telegram Bot SDK、企业微信、Webhook / Bark / Gotify |
| AI 接入 | MCP Java SDK（`/mcp` 端点）、OpenAI 兼容接口 |
| 模板引擎 | Pebble |
| 定时任务 | Quartz |
| 部署方式 | Docker Compose (MySQL + Spring Boot + Nginx) |

## 已完成功能

- [X] 同步任务 / STRM 生成 / 重命名三类任务的配置、执行、记录与重试
- [X] 影视文件重命名与重命名一致性检查（孤儿文件扫描与清理）
- [X] STRM 增量扫描
- [X] PT 订阅：索引器/下载器/媒体服务器（Emby/Jellyfin/Plex）接入、RSS 与自动补搜、洗版、H&R、自动删种、转移做种
- [X] 追剧日历、缺集体检、字幕体检、订阅一键诊断、过滤规则历史回放
- [X] 热门自动订阅（TMDb / RSSHub 豆瓣 / 电影系列 / 人物）
- [X] 通知路由与五个通知渠道，通知快捷操作，TG/企微订阅指令
- [X] MCP 服务端、AI 建过滤规则、AI 标题兜底、每周周报
- [X] 实时日志与历史日志检索、首页待办、数据看板
- [X] 配置备份与恢复
- [X] 移动端适配（H5/PWA）

## 详细教程

安装配置请查看[wiki](https://github.com/907739769/OSR/wiki)，从零开始的完整教程见[博客：OSR 上手指南](https://blog.jackding.cn/archives/osr-shang-shou-zhi-nan-yong-yi-tao-xi-tong-ba-pt-xia-zai-wang-pan-tong-bu-strm-sheng-cheng-he-gua-xue-chong-ming-ming-chuan-qi-lai)

## 演示图

### PC端



<table>
    <tr>
        <td><img src="https://github.com/user-attachments/assets/b349904a-054e-48f4-9133-fe77f0dac5f8"/></td>
    </tr>
    <tr>
        <td><img src="https://github.com/user-attachments/assets/80956447-f498-40b6-9098-b35855f1be14"/></td>
    </tr>

</table>

### 移动端



<table>
    <tr>
        <td><img src="https://github.com/user-attachments/assets/5a6c2c05-da25-45ce-937c-232ec3b1cbf5"/></td>
        <td><img src="https://github.com/user-attachments/assets/e48edb09-bfc5-4d39-8210-66ea3daa7875"/></td>
        <td><img src="https://github.com/user-attachments/assets/ece12a38-32df-4a33-87a1-c998583b2a3e"/></td>
        <td><img src="https://github.com/user-attachments/assets/5a66a223-2e63-4ffe-a2fe-ecd8e5f08065"/></td>
    </tr>
</table>

