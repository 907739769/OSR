# 企业微信接入

自建应用回调的鉴权方式。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **企微回调是 `@Anonymous` 端点**：请求来自企微服务器，不可能带 JWT。安全性靠签名校验 + AES 解密 + receiveid 比对三重保证，三者都依赖只有配置方知道的 Token/AESKey/corpid。回调<b>不做被动回复</b>而是立即返回空串、异步处理完再主动推送——企微要求 5 秒内响应，而建订阅要串行调 TMDb 搜索+详情+媒体库对账，被动回复必然超时并触发企微重试（同一条指令被执行多次）

