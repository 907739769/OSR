# 刮削与文件删除

预览与执行必须共用同一份判定。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **预览与执行必须走同一份判定**：`ScrapeService#resolveScrapeFiles` 解析路径、`deleteScrapeFiles` 遍历它去删，`RenameCleanupService#preview` 也调它。分叉一次就会出现"确认框里列的"和"真正删掉的"对不上，那比不给预览更糟
