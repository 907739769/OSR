## 平台简介

OSR (OpenList STRM Relay)：影视 STRM 管理系统。技术栈：Java 25 (Spring Boot 4.0.6) + Vue 3 + Element Plus + MyBatis-Plus + JWT，Docker 双容器部署。

## 内置功能

### 🎬 STRM 文件生成
- 支持定时任务自动执行、前端页面手动触发、Telegram Bot 指令执行
- 递归扫描网盘目录，为视频文件生成对应的 STRM 流媒体文件
- 支持多任务并行配置与批量执行

### 📂 文件夹同步
- 同步 OpenList 两个文件夹之间的文件（本地 ↔ 云盘）
- 支持定时任务、Telegram Bot、REST API 三种触发方式
- 支持单文件增量同步与全量同步

### 🤖 Telegram Bot 控制
- 通过 Telegram Bot 执行 STRM 生成、文件夹同步等操作
- 支持消息推送与任务状态通知

### 🔗 第三方回调自动化
- 开放 API 接收第三方应用回调通知（如 qBittorrent 下载完成）
- 自动触发文件同步 → 云盘复制 → STRM 生成的完整工作流
- APIKEY 鉴权保障接口安全

### 🔄 影视文件重命名
- 基于 TMDB 元数据自动识别并重命名电影/剧集文件
- 支持重命名任务配置、执行记录查询与重新处理

### 📊 任务管理与监控
- **任务配置**：前端页面可视化配置 STRM 任务、同步任务、重命名任务
- **任务记录**：查看 STRM 生成记录、同步任务记录、重命名记录的执行状态与详情
- **任务操作**：支持单条/批量执行、重新处理、删除网盘文件
- **实时日志**：WebSocket 推送系统日志（info/debug/error 三级），支持移动端自适应展示
- **数据看板**：Dashboard 汇总展示任务统计与运行概况

### 🔐 系统管理
- 用户管理、角色管理、菜单管理、字典管理
- JWT 无状态认证，细粒度权限控制
- 定时任务管理（Quartz），支持 Cron 表达式配置

## 技术栈

| 类别 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.0.6 (Java 25, Preview Features) |
| 前端框架 | Vue 3 + Vite + Pinia + Element Plus + PWA |
| 认证授权 | JWT |
| 数据访问 | MyBatis-Plus 3.5.7 + MySQL 8.0 + Druid |
| JSON | FastJSON2 |
| 消息通知 | Telegram Bot SDK |
| 模板引擎 | Pebble |
| 定时任务 | Quartz |
| 部署方式 | Docker Compose (MySQL + Spring Boot + Nginx) |

## 已完成功能

- [X] 同步任务记录页面、STRM生成记录页面支持单个或多个文件的网盘文件删除
- [X] 同步任务记录页面支持重新处理单条或多条任务记录
- [X] STRM生成记录页面支持重新处理单条或多条任务记录
- [X] 同步任务配置页面支持单个或多个任务执行
- [X] strm任务配置页面支持单个或多个任务执行
- [X] 影视文件重命名功能
- [X] 实时日志监控（WebSocket）
- [X] 数据看板（Dashboard）
- [X] 移动端适配

## 安装配置

安装配置请查看[wiki](https://github.com/907739769/OSR/wiki)

## 演示图

### PC端


<table>
    <tr>
        <td><img src="https://github.com/user-attachments/assets/947f620b-e953-4d40-bff9-a612278ecec7"/></td>
        <td><img src="https://github.com/user-attachments/assets/f74ce0a2-0740-4959-90a8-5711ffaa7ff8"/></td>
    </tr>
    <tr>
        <td><img src="https://github.com/user-attachments/assets/b94e1556-e40e-47d3-9b6f-b31da8632d28"/></td>
        <td><img src="https://github.com/user-attachments/assets/834de439-126e-4851-ac18-209b75b4a59d"/></td>
    </tr>
    <tr>
        <td><img src="https://github.com/user-attachments/assets/5bc78d26-48b4-45cb-944f-52fada4796dd"/></td>
        <td><img src="https://github.com/user-attachments/assets/c2810049-e5c8-4403-aee3-bcffd55a63b4"/></td>
    </tr>

</table>

### 移动端


<table>
    <tr>
        <td><img src="https://github.com/user-attachments/assets/3a9111f5-8a5c-4a79-8464-369db124f127"/></td>
        <td><img src="https://github.com/user-attachments/assets/04bdb2d2-ee5d-4666-8cd9-f4f41848aa35"/></td>
        <td><img src="https://github.com/user-attachments/assets/b6b27293-c655-42b8-bdd0-45bf34944c60"/></td>
        <td><img src="https://github.com/user-attachments/assets/0946b107-235f-4829-94f5-9363abe1f362"/></td>
    </tr>
</table>

