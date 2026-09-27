# Telegram 机器人

> 本文件由根 `AGENTS.md` 的分片机制按需载入。全局约定仍在根 `AGENTS.md`。

## NOTES
- **TG 分两侧，热更新机制不同**：发通知的 `notify/TgNotifier` 每次发送都读配置、按 token/userId 缓存 `TgSendMsg`，天然热更新；
  收指令的长轮询由 `TgBotRegister` 管，启动时注册一次，之后靠 `SysConfigChangedEvent`（`osr-common`，由 `SysConfigServiceImpl`
  在增/改/删/重置缓存后发布）得知 `openlist.tg.*` 变了，**延迟 2 秒合并**后停旧会话、按新配置重新注册。
  延迟不是摆设：备份恢复会逐条写 token、userId，不合并的话中间会拿「新 token + 旧 userId」起一个错的会话；
  同时把注册时的网络请求挪出保存配置的 HTTP 请求。
- **`AbilityBot` 自带的 MapDB 文件库不会被 `onClosing()` 关掉**（6.9.7.1 字节码确认：`TelegramLongPollingBot#onClosing` 只关发送线程池）。
  库文件名固定是 `"bot"`，不关的话热重载新建的实例因文件被锁起不来。所以 `StrmBot#onClosing` 补了 `db.close()`，
  而 `TgBotRegister#launch` 在 `registerBot` 失败时也要主动 `bot.onClosing()`——`StrmBot` 一构造就打开了库，
  注册失败（token 错）后不关，用户改对 token 重试时照样被锁。
- **token 不变、只改 userId 时日志里短暂出现 409 Conflict 是正常的**：`DefaultBotSession#stop()` 只中断线程，
  旧会话挂着的 getUpdates 长轮询要等超时才返回，这段时间新会话会与它冲突。不会丢消息——旧会话不再用新 offset 确认，
  未确认的 update 新会话会重新拿到。
- 注册失败时 `TgBotRegister` **不记下 running 状态**，所以用户原样再保存一次（或点「刷新缓存」）就会重试。
