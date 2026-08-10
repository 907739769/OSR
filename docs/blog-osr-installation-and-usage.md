# OSR 上手指南：用一套系统把 PT 下载、网盘同步、STRM 生成和刮削重命名串起来

> 本文基于 OSR 仓库代码与官方 Wiki 整理，覆盖从 Docker 部署到 PT 订阅全链路自动化的完整流程。
> 项目地址：[github.com/907739769/OSR](https://github.com/907739769/OSR) ｜ Wiki：[项目 Wiki](https://github.com/907739769/OSR/wiki)

## 一、OSR 是什么

OSR 的全称是 **OpenList STRM Relay**，是一套围绕 **OpenList（AList 分支）** 构建的影视 STRM 管理系统。

如果你的观影链路是这样的：

```
PT 站点 → qBittorrent 下载到本地 → 上传/同步到网盘 → 生成 STRM → Emby/Jellyfin 播放
```

那么中间那几步「同步」「生成 STRM」「按 TMDb 规范重命名」「下完通知我一声」，基本都是靠脚本、cron 和人肉巡检拼起来的。OSR 做的事情就是把这条链路做成一个有界面、有记录、有通知、可重试的系统：

- **STRM 生成**：递归扫描网盘目录，为视频文件生成对应的 `.strm` 流媒体文件；
- **文件夹同步**：本地 ↔ 网盘、网盘 ↔ 网盘的增量/全量同步，带复制任务监控与重启兜底；
- **TMDb 刮削与重命名**：按元数据识别电影/剧集，模板化重命名，并生成 NFO、海报等刮削产物；
- **一致性检查**：双向孤儿扫描，找出「记录在库里、文件没了」和「文件在库里、记录没了」的残骸；
- **PT 订阅**：接入 Torznab 索引器与下载器，RSS 自动追剧、缺集补搜、洗版、H&R 追踪、自动删种；
- **第三方回调**：开放 API 接收 qBittorrent 等下载完成通知，自动触发同步 → 复制 → STRM 全流程；
- **多端通知与控制**：Telegram Bot、企业微信自建应用、通用 Webhook 三条通知渠道，前两者还能反向下指令。

技术栈上是 Java 25（Spring Boot 4.0.6，开了 Preview 特性用虚拟线程）+ Vue 3 + Vuetify 3 + MyBatis-Plus + MySQL 8.0，前端带 PWA，移动端可以直接「添加到主屏幕」当 App 用。

---

## 二、安装部署

### 2.1 前置要求

| 项 | 要求 |
|---|---|
| Docker | 20.10+ |
| Docker Compose | v2 |
| 内存 | 至少 2GB 可用 |
| 磁盘 | 按 STRM/日志/上传量准备，建议 10GB 起 |
| 依赖服务 | 一个可访问的 OpenList/AList 实例（必需） |

### 2.2 准备部署目录

最简单的方式是直接克隆仓库：

```bash
git clone https://github.com/907739769/OSR.git
```

如果只想部署、不想要源码，也可以只下载 `docker-compose.yml`、`.env.example`、`nginx.conf` 三个文件到一个自建目录里。

### 2.3 配置环境变量

复制 `.env.example` 为 `.env`，逐项改掉里面的占位值：

```bash
cp .env.example .env
```

```ini
# MySQL 配置
MYSQL_ROOT_PASSWORD=你的强密码
MYSQL_DATABASE=osr

# 应用专用数据库账号（非 root，仅拥有该库权限，MySQL 镜像会自动创建）
DB_USERNAME=osr_app
DB_PASSWORD=你的强密码

# JWT 密钥，至少 32 字符，建议 64 位随机串
JWT_SECRET=请替换为强随机字符串

# PT 下载器密码 / 索引器 apikey 的加密密钥，至少 32 字符
PT_CREDENTIAL_SECRET=请替换为强随机字符串

# 时区
TZ=Asia/Shanghai

# 前后端分域部署时才需要，生产环境由 Nginx 同源代理 /api，通常留空
# CORS_ALLOWED_ORIGINS=http://your-domain:80
```

几个容易踩的点：

- `MYSQL_ROOT_PASSWORD` 和 `DB_PASSWORD` 是两个独立的东西，后端连库用的是 `DB_USERNAME/DB_PASSWORD` 这个最小权限账号，不复用 root；
- `PT_CREDENTIAL_SECRET` 不配也能跑，但会退化成内置兜底密钥并打警告日志——它只能保证下载器密码、索引器 apikey 不明文落库，不具备真正的机密性，**生产环境务必显式配置**；
- `JWT_SECRET` 改了之后所有人都要重新登录，这是预期行为。

生成随机串可以直接用：

```bash
openssl rand -base64 48
```

### 2.4 使用预构建镜像（推荐）

仓库里的 `docker-compose.yml` 默认是本地构建（`build:`），需要先 `mvn package` 才能用。日常部署建议改成官方镜像，把 `backend`、`frontend` 两个服务的 `build:` 段换成 `image:`：

```yaml
  backend:
    image: jacksaoding/osr-backend:latest
    # build:
    #   context: .
    #   dockerfile: Dockerfile.backend

  frontend:
    image: jacksaoding/osr-frontend:latest
    # build:
    #   context: .
    #   dockerfile: Dockerfile.frontend
```

### 2.5 启动

```bash
docker compose pull
```

```bash
docker compose up -d
```

首次启动时 MySQL 要初始化，后端会等健康检查通过后再连库并自动执行建表/迁移脚本，耐心等一两分钟。

### 2.6 验证

```bash
docker compose ps
```

```bash
docker compose logs -f backend
```

`docker compose ps` 里三个容器都是 `running`、`restarts` 为 0 才算正常。

**排查启动失败的关键**：Java 异常写在容器内的 `/data/logs/sys-error.log`，**不在 docker stdout**（stdout 只有启动 banner）。容器反复重启时，先让它崩溃后停住再看日志：

```bash
docker update --restart=no osr-backend && docker restart osr-backend
```

然后把日志捞出来看：

```bash
docker cp osr-backend:/data/logs ./tmp
```

### 2.7 端口与数据卷

| 端口 | 服务 | 用途 |
|---|---|---|
| 80 | frontend (Nginx) | Web 界面 + `/api` 反代 + WebSocket 代理 |
| 6895 | backend | 后端 API |
| 3306 | mysql | 数据库 |

| 卷 | 说明 |
|---|---|
| `/data` | 宿主机挂载，存放 `upload/`、`logs/`、`strm/` |
| `mysql-data` | 数据库数据 |

端口冲突就改 `docker-compose.yml` 里的 `ports` 映射，比如把 `"80:80"` 改成 `"8080:80"`。

### 2.8 首次登录

浏览器打开 `http://你的IP:80`：

- 用户名：`admin`
- 密码：`openliststrm666`

**登录后第一件事：点右上角头像改掉默认密码。**

---

## 三、基础配置

### 3.1 配置 OpenList 连接

进入 **系统管理 → 参数管理**，这里是 OSR 所有全局开关的所在地。必填的只有两项：

| 参数键 | 名称 | 说明 |
|---|---|---|
| `openlist.server.url` | OpenList 访问地址 | 如 `http://192.168.1.10:5244` |
| `openlist.server.token` | OpenList API token | 在 OpenList 后台「其他」页面获取 |

强烈建议顺手配上的：

| 参数键 | 名称 | 说明 |
|---|---|---|
| `openlist.api.apikey` | OSR 接口 Apikey | 第三方回调（qB 等）的鉴权凭证，不配则回调接口不可用 |
| `openlist.tmdb.apikey` | TMDb API Key | 重命名/刮削/PT 订阅都依赖它 |

可选的 AI 兜底（文件名正则和 TMDb 都识别不出来时才调用）：

| 参数键 | 默认值 | 说明 |
|---|---|---|
| `openlist.openai.endpoint` | 官方地址 | 支持任意 OpenAI 兼容端点 |
| `openlist.openai.apikey` | 空 | |
| `openlist.openai.model` | `gpt-5-mini` | |

> 注意：**Telegram Bot 相关参数改完需要重启后端才生效**；TMDb / OpenAI 参数在每次重命名时实时读取，改完立即生效。

### 3.2 值得了解的性能与行为开关

这几个参数默认值就很合理，但了解它们能帮你在大库场景下调优：

| 参数键 | 默认 | 作用 |
|---|---|---|
| `openlist.strm.outputdir` | `/data/strm` | STRM 文件生成根目录 |
| `openlist.strm.encode` | `0` | STRM 内路径是否 URL 编码 |
| `openlist.strm.downloadsub` | `0` | 生成 STRM 时是否顺带下载字幕 |
| `openlist.copy.minfilesize` | `10` | 复制的最小文件大小（MB），过滤掉样片、说明文件 |
| `openlist.copy.strm` | `1` | 复制完成后是否自动生成 STRM |
| `openlist.api.refresh` | `1` | **源目录**同步列举时强制刷新网盘，保证增量正确性 |
| `openlist.api.traversal.refresh` | `0` | **目标目录**遍历时是否强制刷新。默认走缓存，对网络盘快非常多 |
| `openlist.api.traversal.concurrency` | `10` | 目录遍历并发度，范围 1–64。大目录树调高能显著提速 |
| `openlist.copy.monitor.maxminutes` | `600` | 复制任务监控最长时长，超时标记异常，避免无限期挂着 |
| `openlist.local.allowedroots` | `/data` | 本地目录浏览接口的根目录白名单，防止管理端枚举整个宿主机文件系统 |
| `openlist.tmdb.metadata.language` | `zh-CN` | TMDb 元数据语言 |
| `openlist.tmdb.image.language` | `zh` | TMDb 图片语言偏好 |
| `openlist.tmdb.image.size` | `original` | 图片尺寸，可改 `w780/w500` 省带宽和存储 |

### 3.3 配置路径映射

进入 **OpenListStrm → 路径管理**，把「本地路径」和「网盘路径」建立对应关系。核心要求是**两侧的目录树结构必须一致**，OSR 才能把一侧的相对路径原样映射到另一侧。

举个例子：

```
本地：/download/pt/电影/华语电影/功夫/功夫.mp4
网盘：/115网盘/影视/pt/电影/华语电影/功夫/功夫.mp4
       └────┬────┘ └──────────┬──────────────┘
       映射前缀差异        结构必须完全相同
```

这一步配错，后面同步任务和回调自动化都会「跑成功但文件放错地方」，值得多花两分钟核对。

---

## 四、核心功能怎么用

### 4.1 同步任务

**OpenListStrm → 同步任务配置** → 新建：

1. 填任务名；
2. 选源路径与目标路径（网盘 ↔ 网盘、网盘 ↔ 本地都支持）；
3. 设置任务启用状态；
4. 保存后可以手动执行，也可以交给定时任务。

执行结果在 **同步任务记录** 页查看。这个页面支持：

- 单条 / 批量**重新处理**失败记录；
- 单个 / 多个**网盘文件删除**。

### 4.2 STRM 任务

**OpenListStrm → STRM 任务配置** → 新建：

1. 填任务名；
2. 配置扫描路径、输出目录、文件过滤规则；
3. 保存后手动执行或等定时任务。

结果在 **STRM 生成记录** 页查看，同样支持单条/批量重新处理与网盘文件删除。

### 4.3 重命名任务

前置条件：参数管理里配好 `openlist.tmdb.apikey`；想要 AI 兜底的话再配上 OpenAI 三项。

**OpenListStrm → 重命名任务配置** → 新建，指定源目录和目标目录，保存后执行。

识别链路是：**本地正则抽取 → TMDb 增强 → AI 补充（仅在前两步都识别不出时）→ 模板渲染**。识别结果、原文件名到目标文件名的映射，都能在**重命名明细**页逐条查看。

有几个设计上的取舍值得知道：

- **目标库里的主文件是 `Files.copy` 出来的副本**，源文件（下载器的保种目录、网盘挂载点）OSR 一律不动——所以系统里**根本没有「删除源文件」的入口**，这是刻意的：删了它等于毁保种。
- 「**删产物**」和「**删记录**」是两件不同的事，界面上也是分开的。只删数据库记录会导致三个后果：孤儿扫描再也发现不了那批文件、刮削共享元数据（`tvshow.nfo` 等）的兄弟计数算错、手动重跑任务时会把源文件当成没处理过再复制一份出来。前端确认框里会把这几条写清楚。

### 4.4 定时任务

**系统监控 → 定时任务** 里内置了三个作业：

| 任务 | 默认时间 |
|---|---|
| 重命名任务 | 每天 02:00 |
| 同步任务 | 每天 03:00 |
| STRM 任务 | 每天 05:00 |

可以改 Cron 表达式、手动触发、停用。**但不要改调用目标字符串**。

### 4.5 一致性检查（孤儿扫描）

**OpenListStrm → 重命名一致性检查**。它是双向扫描：

- **正向**（记录 → 文件）：数据库里有记录，目标位置的文件不见了；
- **反向**（文件 → 记录）：目标库里有文件，却找不到对应记录，细分为 `local_extra`（多余文件）、`metadata_only`（只有元数据没有视频，Emby 里会显示成一个空剧集）、`empty_dir`（空目录）。

反向扫描带 **mtime 基线**：早于对应任务创建时间的文件一律不上报。判据是「任务建起来之前就躺在库里、之后又没被动过的东西不是它产出的」——没这道闸，一个混着历史文件的媒体库每轮会报出成千上万条无关项。

发现的项可以**清理**或**忽略**，忽略过的项后续轮次直接跳过，不会反复打扰。

### 4.6 实时日志与看板

- **实时日志**：WebSocket 推送，info / debug / error 三级，移动端自适应；
- **数据看板**：首页汇总各类任务的统计与运行概况。

---

## 五、把 qBittorrent 接进来：下载完成自动上云 + 生成 STRM

这是 OSR 最实用的自动化之一：qB 下载完成 → 回调 OSR → 同步到网盘 → 生成 STRM，全程无人值守。

### 5.1 前置条件

1. 参数管理里配好 `openlist.api.apikey`；
2. qBittorrent 所在网络能访问到 OSR 的 6895 端口（或走前端 80 端口的 `/api` 反代）；
3. 本地目录、网盘目录、qB 下载目录三者的目录结构对得上（见 3.3）。

### 5.2 接口说明

```
POST /api/openliststrm/notify/notifyByDir
Header: X-API-KEY: <openlist.api.apikey 的值>
Content-Type: application/json
```

请求体四个字段全部必填：

```json
{
  "srcDir": "/download/pt",
  "srcDst": "/115网盘/影视/pt",
  "qbDlRootPath": "/downloads",
  "qbDlFilePath": "/downloads/电影/华语电影/功夫/功夫.mp4"
}
```

| 字段 | 含义 |
|---|---|
| `srcDir` | 源目录（本地下载根目录在 OSR/OpenList 视角下的路径） |
| `srcDst` | 目标目录（对应的网盘路径） |
| `qbDlRootPath` | qB 的下载根目录 |
| `qbDlFilePath` | 本次下载完成的资源路径 |

OSR 拿到后会用 `qbDlFilePath` 去掉 `qbDlRootPath` 前缀得到相对路径，再判断是视频文件还是目录：视频走单文件同步，目录走批量同步。

### 5.3 qB 侧配置

在 qB 的 config 目录下建一个 `notify.sh`（按自己的路径改前三个变量）：

```bash
#!/bin/sh
srcDir="/download/pt"
srcDst="/115网盘/影视/pt"
qbDlRootPath="/downloads"
apiKey="你在参数管理里配的 apikey"
osrUrl="http://osr-host:6895"

curl -s -X POST "${osrUrl}/api/openliststrm/notify/notifyByDir" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: ${apiKey}" \
  -d "{\"srcDir\":\"${srcDir}\",\"srcDst\":\"${srcDst}\",\"qbDlRootPath\":\"${qbDlRootPath}\",\"qbDlFilePath\":\"$2\"}"
```

然后在 qB 的「下载完成时运行外部程序」里填：

```
sh /config/notify.sh "%G" "%F"
```

---

## 六、PT 订阅：把追剧也自动化

这是 OSR 里最复杂、也最有意思的模块。它做的事情是：你订阅一部剧，剩下的搜索、筛选、推送下载、进度追踪、入库确认、缺集补搜、洗版、H&R 保种提醒，全部自动完成。

### 6.1 前置条件

| 组件 | 说明 |
|---|---|
| Torznab 索引器 | Jackett 或 Prowlarr，至少配好一个 PT 站 |
| 下载器 | qBittorrent 或 Transmission，OSR 能访问到 |
| 媒体服务器 | Emby 或 Jellyfin，**必需** |
| TMDb API Key | 参数管理里的 `openlist.tmdb.apikey` |

媒体服务器为什么是必需的：**OSR 判断「这一集齐了」的依据不是下载完成，而是媒体服务器的库里真的能查到它**。下完 ≠ 活干完——文件还要传上网盘、还要生成 STRM、Emby 还要扫到，中间任何一步断了这一集就不算数。

### 6.2 配置索引器

**OpenListStrm → PT 索引器** → 新增：

- 索引器名称、Torznab URL（从 Jackett/Prowlarr 复制）、API Key；
- 分类代码（剧集 `5000`、电影 `2000`）；
- 轮询周期，默认 600 秒。

轮询周期不要设太短。系统内部对同一索引器有最小 2 秒间隔的限流，撞到站点 429 之后会进入 300 秒冷却——冷却期内的请求会走「背压」分支快速失败，这类失败**既不计入失败次数也不清零**，专门避免「限流 → 退避放大 → 拉取窗口从几分钟变成几小时」的正反馈。

系统还会做 **RSS 覆盖度校验**：记录上一轮的最大发布时间作为游标，判断本轮的窗口下沿是否覆盖得上。覆盖不全会告警提醒你缩短周期。（这里刻意用「时间位置」而不是「上一轮首条种子还在不在」当游标——后者在有置顶种子、种子被删、guid 带一次性 token 的站上会恒定误报。）

### 6.3 配置下载器

**OpenListStrm → PT 下载器** → 新增：

- 类型（qBittorrent / Transmission）、地址、账号密码（支持 HTTPS）；
- **保存路径必须落在某个已启用的同步任务的源目录内**——这是硬要求，否则下载完的文件不会流进「同步到网盘 → 生成 STRM」这条管道；
- 标签，默认 `osr-pt`（OSR 靠它认领自己推的种子）；
- 最大并发下载数；
- **角色**：`DOWNLOAD`（订阅下载池）或 `SEED_ONLY`（只做种的保种机）。

角色这个字段值得单独说：如果你加了一台「接 IYUU 转移+辅种、开着自动删种」的保种机，不把它标成 `SEED_ONLY` 的话，订阅会把新剧集也往它上面推——而那台机器的清理规则是按保种设计的（做满 N 小时就删），正在补的剧集下上去会被当成保种种子清掉。

### 6.4 过滤规则（可选但强烈建议）

**OpenListStrm → PT 过滤规则**：

- **硬过滤**：最小做种数、体积上下限（支持按集折算）、分辨率/片源/标签要求、是否必须带中文字幕、是否规避 H&R 站点；
- **排序维度**：默认 `RESOLUTION,FREE,SEEDERS,SIZE`，可调整优先级。

### 6.5 创建订阅

**OpenListStrm → PT 订阅**：

1. 搜索剧名/片名（走 TMDb）；
2. 选中作品；剧集还要选季；
3. 保存——保存那一刻会立即触发一次补充搜索，不用等下一轮 RSS。

### 6.6 后台是怎么跑的

创建完订阅后，四个后台任务接管一切：

| 任务 | 周期 | 职责 |
|---|---|---|
| RSS 轮询 | 60 秒 | 拉索引器最新种子，匹配订阅 |
| 自动补搜 | 30 分钟 | 按订阅粒度主动搜缺失集 |
| 下载追踪 | 30 秒 | 跟进度、选文件、排除非目标集 |
| 媒体库同步 | 10 分钟 | 去 Emby/Jellyfin 确认这一集真的入库了 |

单集状态机：

```
MISSING（缺失，等待匹配）
   ↓ 推送到下载器
IN_FLIGHT（在途）
   ↓ 下载完成 + 同步上云 + 媒体库确认
IN_LIBRARY（已入库，完成）
   ↓ 触发洗版
UPGRADING（下载更高画质版本中）

任意环节失败 3 次以上 → BLOCKED（熔断，需人工在下载记录页重试）
```

几个实现细节挺能体现这个模块的成熟度：

- **多集包以暂停态推送**：推送那一刻不知道包里有哪几集，先暂停，等下载器返回文件列表、选出目标集文件后再启动。单集/电影不暂停（没有选错的可能），磁力链也不暂停（暂停态下下载器不下元数据，会永远卡住）。
- **包内一个目标集都没有时会中止**，而不是照常下发排除指令——把全部文件设成不下载会得到一个 0 字节、永远挂着的僵尸任务，白占并发名额。
- **标题里没集号时会去种子 description 里找**。有一类日更剧发布组把同一季每一集都发成标题逐字相同的种子，集号只写在描述里，不解析的话会被反复当成整季包白推。
- **OSR 默认从不删种**，只有两个受控例外：从未下完也从未做种的废种，以及你逐个下载器显式开启的自动删种。

### 6.7 进阶功能

**洗版（画质升级）**：在升级规则里开启，**必须指定 cutoff 目标**（分辨率/片源/标签至少填一项）——没有终止条件的话每一集都会永远搜下去把索引器配额烧干，所以三项全空时洗版不激活。判定维度只有分辨率、片源、标签、发布组四项，**刻意不引入做种数/体积/免费状态**：那些值随时间连续变化，会导致无限循环洗版。洗版不会自动删旧文件。

**自动追剧规则**：订阅 TMDb 的热门/趋势榜单，可设最低评分、排除类型、限定地区。

**种子黑名单**：拉黑特定发布组或种子，后续不再匹配。

**H&R 追踪**：按索引器配置最短做种时长和分享率要求（**或**关系，达标任一即可）。H&R 是站点属性不是种子属性——Torznab 协议没有标准 H&R 字段，只能整站判定。OSR 只做告警，不会也无法自动补救。追踪是**跨下载器**的：种子被 IYUU 转移走了会去其它下载器的快照里找回来，而不是直接判成违规。

**自动删种**：按体积区间 + 做种时长分级删除（比如 >50GB 做满 3 天删、其余做满 7 天删）。五条护栏一条都没省：

1. 总开关默认关，且要逐个下载器显式开启；
2. 没有启用任何规则就一个都不删（空规则集 = 没有规则说该删它）；
3. H&R 考核中的记录一律保护；
4. **还有集停在在途/洗版中的记录同样保护**——种子下完不等于活干完，文件还要传网盘，大文件跨天是常态；
5. 每轮有删除上限，超出的会记日志而不是静默截断。

删除以**辅种组**为最小单位（按内容路径分组），组内每个种子都达标才整组删，任一个不达标整组保留——删掉一个种子的文件会让共用这份文件的其它种子立刻变成「文件丢失」，那是在**别的站**上记 H&R。

### 6.8 PT 排障速查

| 现象 | 排查方向 |
|---|---|
| 一直不下载 | 看通知里的过滤淘汰原因；检查索引器状态；看「搜索淘汰原因」看板 |
| 下载完了还显示在途 | 同步任务/STRM 任务有没有执行；媒体服务器连通性；文件到底传上网盘没有 |
| 某集卡住不动 | 大概率已 `BLOCKED`，去下载记录页手动重试 |
| 反复失败 | 看「搜索淘汰原因」统计，通常是过滤规则卡太死 |

---

## 七、通知与远程控制

OSR 有三条通知渠道，都支持按类型过滤（`GENERAL`、`SUBSCRIPTION_HIT`、`DOWNLOAD_COMPLETE`、`DOWNLOAD_FAILED`、`EMBY_LIBRARY_SYNC`，留空 = 全发）。

### 7.1 Telegram Bot

参数管理里配 `openlist.tg.token` 和 `openlist.tg.userid`，**重启后端**生效。支持的指令：

| 指令 | 作用 |
|---|---|
| `/strm` | 执行 STRM 任务 |
| `/strmdir` | 生成指定路径的 STRM |
| `/sync` | 执行同步任务 |
| `/syncdir` | 同步 OpenList 指定目录 |
| `/rename` | 执行重命名任务 |
| `/retry` | 重试所有失败任务 |
| `/checkorphan` | 执行重命名一致性检查 |

类型过滤：`openlist.notify.tg.types`。

### 7.2 企业微信自建应用

比 TG 更适合国内网络环境，而且是**订阅助手**式的多轮交互。需要配置的参数：

| 参数键 | 说明 |
|---|---|
| `openlist.wecom.corpid` | 企业 ID，留空则企微功能整体不启用 |
| `openlist.wecom.agentid` | 自建应用 AgentId |
| `openlist.wecom.secret` | 自建应用 Secret |
| `openlist.wecom.token` | 回调 Token，不配则只能发通知、收不到指令 |
| `openlist.wecom.aeskey` | 回调 EncodingAESKey（43 位） |
| `openlist.wecom.touser` | 无归属通知的接收人，默认 `@all` |
| `openlist.wecom.proxy` | API 代理地址，默认官方地址 |
| `openlist.wecom.autocreate` | 成员首次发指令时自动建号绑定，默认**开启** |

> 💡 2022-06-20 之后创建的自建应用调用企微 API 必须登记「企业可信 IP」，家宽/动态 IP 登记不了。通行做法是自己反代 `qyapi.weixin.qq.com`，把中转地址填进 `openlist.wecom.proxy`。

支持的指令：

```
订阅 <剧名>       搜索剧集并订阅，如：订阅 三体
订阅电影 <片名>   搜索电影并订阅
我的订阅          查看自己的订阅列表
下载中            查看正在下载的集
最近入库          查看最近入库的集
进度 <编号>       查看某条订阅的进度
暂停 <编号>       暂停订阅
恢复 <编号>       恢复订阅
我的账号          查看绑定状态
取消              中断当前的多轮选择
帮助              显示本说明
```

搜索后直接回复序号即可选择。订阅是带**归属**的：某条订阅的动态只推给它的所有者，不会把 A 的下载进度推到 B 的企微上（历史遗留的无归属订阅退化为广播，所有人可见）。

### 7.3 通用 Webhook

配 `openlist.notify.webhook.url`，OSR 会 POST 一个 `{"text": "消息内容"}` 过去，留空即不启用。类型过滤：`openlist.notify.webhook.types`。

---

## 八、日常运维

### 8.1 备份

数据库：

```bash
docker exec osr-mysql mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" osr > osr-backup.sql
```

文件（STRM、日志、上传）直接备份宿主机的 `/data` 目录即可。

### 8.2 升级

```bash
docker compose pull && docker compose up -d
```

自建镜像的话：拉代码 → `mvn clean package -DskipTests` → 前端 `cd osr-web && npm run build` → `docker compose up -d --build`。

**升级前先备份数据库**。建表和迁移脚本由后端启动时自动执行，不需要手动导入。

> ⚠️ 自建前端镜像有个坑：`Dockerfile.frontend` 不是多阶段构建，它只是把 `osr-web/dist` COPY 进 Nginx 镜像，**不会自己跑 `npm run build`**。改了前端代码不先手动构建的话，`COPY` 这层会命中 Docker 缓存，容器里跑的还是旧代码（构建日志里这行显示 `CACHED` 就是没生效的信号）。

只更新单个服务：

```bash
docker compose up -d --build --no-deps frontend
```

### 8.3 常见问题

**服务起不来？** 确认 Docker 正常、`.env` 已配置、MySQL 初始化完成（首次启动需要时间），然后看 `/data/logs/sys-error.log`。

**改数据库密码？** `MYSQL_ROOT_PASSWORD` 和 `DB_PASSWORD` 是独立的，改完要重建容器才生效。

**改 JWT_SECRET？** 至少 32 字节，改完重启后端，所有用户需重新登录。

**前端访问不了 API？** 依次检查后端容器是否运行、后端日志有无报错、端口映射是否正确、前后端分域时 `CORS_ALLOWED_ORIGINS` 有没有配。

**定时任务不执行？** 检查任务是否启用、Cron 表达式是否正确、日志里有无异常。

**能配多个 OpenList 实例吗？** 当前版本只支持单实例。

**新增了 SQL 迁移脚本却没执行？** 这是开发向的坑：`MysqlDdl.getSqlFiles()` 是**硬编码的文件名清单，不是目录扫描**，新脚本必须手动追加到列表末尾。

---

## 九、写在最后

OSR 的定位很清楚：它不试图取代 Emby/Jellyfin，也不取代 OpenList，而是把「下载器 → 网盘 → STRM → 媒体库」这条链路上所有需要人肉盯着的环节接管过来，并且在每个环节留下可查询、可重试的记录。

如果你只是想要 STRM 生成，配好 OpenList 地址和 token，建一个 STRM 任务就能用；如果你想要全自动追剧，那就沿着「索引器 → 下载器 → 媒体服务器 → 过滤规则 → 订阅」的顺序一路配下来。两种用法都成立，中间的每一档也都成立。

- 项目地址：[github.com/907739769/OSR](https://github.com/907739769/OSR)
- 完整文档：[项目 Wiki](https://github.com/907739769/OSR/wiki)
