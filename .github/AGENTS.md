# CI 与发版流程

推 tag、构建镜像、手工写中文更新日志。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **发版必须手工写中文更新日志，CI 自动生成的那份不算数**。流程是：推 tag（`vX.Y.Z`，附注标签，message 写成 `vX.Y.Z: 一句话说清这版的主线`）→ `.github/workflows/docker-publish-tag.yml` 构建并推送镜像、然后用 `generate_release_notes: true` 建出 release → **再把更新日志写进 release 正文**（`gh release edit <tag> --notes-file <file>`）。自动生成的正文只有一串 commit 标题和一个 compare 链接，回答不了用户真正要问的三件事：这版对我有什么影响、升级要不要做什么、出问题时能不能退。**漏了这一步等于没发版**——用户不读 commit，只读 release。
  - 正文结构固定为 `## ✨ 新增功能` / `## 🐛 Bug Fixes` / `## ⚙️ Improvements` / `## 📦 Breaking Changes` / `## 📝 Notes`，五节都要在，没有内容就写「无」（少一节读者会以为漏写了，写「无」是一个明确的信号）。末尾保留 CI 给的 `**Full Changelog**: .../compare/<上一版>...<本版>` 那行。
  - **写「为什么」而不只是「改了什么」**。已发布的几版都是这个调子：先说清用户看到的现象，再说清成因，最后才是做法——照抄 v3.3.7、v3.3.8 的写法即可。`📝 Notes` 里固定交代：有没有新增数据库迁移脚本（有的话写明**启动时自动执行、不需要手动跑 SQL**）、升级后第一天有没有需要预期的异常观感、以及本版新增/改动的可调配置项。
  - **版本号按 patch 递增，功能也走 patch**（v3.3.5 整个「转移做种」功能就是一次 patch 升位），不要因为是新功能就自己抬 minor。
  - **构建失败时不要手工补建 release**。`create-release` 逐个判依赖的 success/skipped，构建真失败就跳过——那是有意的，发一个装着旧镜像的版本比不发版糟得多。正确做法是 `gh run rerun <id> --failed`，先确认失败是 GitHub 侧的（429/502/503 一类）还是自己的代码问题。反过来，**只有一侧改动导致另一侧 job 被 skip 是正常的**，那种情况 release 照常建（v3.3.6 就是因为把 skip 也当成失败而漏建过一次）。**但 skip 的判据本身要盯住**：`detect-changes` 是按路径列表判的，而<b>改了什么文件</b>与<b>那个文件进哪个镜像</b>是两回事——`nginx.conf` 在仓库根目录，却由 `Dockerfile.frontend` COPY 进前端镜像，此前不在前端检测路径里，于是「只改 nginx.conf」的版本会跳过前端构建、发出去的镜像里装着上一版的 nginx 配置，且不报任何错（v3.3.31 加 `/mcp` 那条 location 时正好撞上，因为前端代码也改了才没暴露）。新增任何「被 Dockerfile COPY 进镜像、但不住在对应模块目录下」的文件时，都要同步往那份路径列表里加一条。
  - 顺带：只改了 `osr-web/` 时本地验证要先 `cd osr-web && npm run build` 再 `docker compose up -d --build --no-deps frontend`，否则 `COPY osr-web/dist` 那层会命中缓存（见下方 NOTES 里 `Dockerfile.frontend` 那条）。
