# MyBatis-Plus 数据层

实体、Mapper、Wrapper 的陷阱。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **`LambdaQueryWrapper#select(实体::getXxx)` 会立刻解析 MyBatis-Plus 的实体 lambda 缓存**，那份缓存要等 Mapper 注册后才有。纯单测里直接 new 出 Service 再调用会抛 `can not find lambda cache for this entity`（`eq`/`isNotNull` 是惰性的所以不炸，只有 `select` 会）。需要投影列时改用 `QueryWrapper` 传列名字符串
