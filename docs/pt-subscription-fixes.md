# PT 订阅正确性修复

2026-11-07。对应 [分析报告](pt-subscription-audit.md) 中 9 个已复现场景；代码修复没有自动处理用户既有错下资源，也没有部署至用户实际服务。

## 已完成

| 原问题 | 修复后的行为 |
| --- | --- |
| 《谷雨》误认领英文同名的《宿敌》 | 副标题独立年份段参与校验；英文主标题命中后仍检查可靠副标题中文名反证。用户原始主副标题在 RSS、关键词补搜和手动推送均被拒绝 |
| ID 搜索结果没有可比身份也直接放行 | UNKNOWN 回退片名校验，电影还需年份；明确 ID 冲突拒绝，明确相同 ID 支持不同译名 |
| 新同名剧认领旧剧 | 第一季增加双向一年容差；多个订阅都能匹配且缺少消歧证据时不按数据库顺序下载 |
| 包内其他季的同集号被保留 | 选文件和完成前补确认共用季集范围判断；字幕/NFO 不证明视频存在；多集视频覆盖整个区间 |
| 绝对编号范围被原值覆盖 | RSS 的绝对编号区间在推送、占位、记录中保持本季编号，例如 51～66 → 本季 1～16 |
| 不合格完整包挤掉合格部分包 | 先过硬规则，再在合格候选中比较覆盖度 |
| 季搜索有不合格候选就不补搜单集 | 根据推送 code 区分可扩搜的候选问题与容量、连接、持久化等等待问题 |
| 一台下载器满载便跳过其他机器 | 自动分配先排除满载机器；用户显式指定下载器仍保留等待语义 |
| 索引器故障被当作没有片源 | 全失败、部分失败、预算/补发上限截断和容量等待不推进有效检索时间，不累计片源落空退避；下一心跳继续处理 |

手动候选增加绑定订阅的服务端快照（15 分钟、最多 5000 条）。前端回传 `candidateId`，后端恢复搜索时的完整身份画像，再检查当前订阅、站点和过滤规则。候选过期或跨订阅使用明确要求重搜；旧页面和脚本无 token 的请求继续执行反证与规则校验。

## 复用接口的依据

以下方法均先核对实现，再用于修复；本节也明确它们的取值约定。

| 接口 | 实际语义与输入约定 | 实现 |
| --- | --- | --- |
| `MediaParser.parseLocal(String)` | 返回本地 MediaInfo；片名取 originalTitle/englishTitle，title 不做 TMDb 富化；不请求 TMDb/AI | [MediaParser.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/rename/MediaParser.java) |
| `DescriptionAliases.parse(String)` | description 可空；只取首段，返回原始别名列表，调用方再归一化；不采集后面的题材或演员字段 | [DescriptionAliases.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/DescriptionAliases.java) |
| `SeasonSuffix.strip(String)` | 剥标题尾部季号；null 返回 null，剥空保留原值 | [SeasonSuffix.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/rename/SeasonSuffix.java) |
| `AbsoluteEpisodeMap.toLocalRange(Integer,Integer,Integer)` | 返回本季 LocalRange 或 null；须非空映射、来源季号缺失或为 1、区间两端都属于本季；返回 null 不代表已知为空 | [AbsoluteEpisodeMap.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/AbsoluteEpisodeMap.java) |
| `OpenListHelper.isVideo(String)` | 根据配置中的视频扩展名返回 boolean；不以“能解析出集号”替代视频判断 | [OpenListHelper.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/helper/OpenListHelper.java) |
| `FilterCriteriaFactory.build(global,override)` | 合并全局规则与订阅覆盖；继续沿用原规则与大小写/单位约定 | [FilterCriteriaFactory.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/filter/FilterCriteriaFactory.java) |

新增结果约定：

- `PushOutcome.code` 供自动编排判断，reason 继续供页面和日志展示；原 boolean 推送入口保持兼容。
- `SearchAttempt` 是一次请求的局部状态，显式传递，不存入单例 Service 的请求状态字段。
- `SearchAndPushSummary.retrySoon` 表示检索/推送暂未完成；不等同于“没有资源”。
- 文件覆盖：null 表示无法解析，空集合表示明确属于其他季，非空集合是本地集号；只有视频可以贡献文件存在确认。

## 验证

后端全量执行：

```bash
mvn -pl osr-admin -am package
```

结果：全量 2,239 个测试通过，0 失败、0 错误、0 跳过；后端所有模块打包成功。随后增加“部分站点故障”和“补发上限截断”两个检索完整性回归，定向执行 `SubscriptionSafetyRegressionTest` 的 15 个用例也全部通过。

核心新增回归：

- [SubscriptionSafetyRegressionTest.java](../osr-openliststrm/src/test/java/com/osr/openliststrm/pt/subscription/SubscriptionSafetyRegressionTest.java)：真实解析、匹配、过滤、补搜和推送，外部网络与数据库使用替身；覆盖用户原始错误资源、绝对号、候选回退、容量、失败退避、候选快照与篡改防护。
- [DownloadTrackServiceTest.java](../osr-openliststrm/src/test/java/com/osr/openliststrm/pt/task/DownloadTrackServiceTest.java)：新增别季同号、多季包字幕选择、字幕不能证明视频存在、多集视频、已完成磁力释放错误占位等场景。
- [usePtSubscription.spec.ts](../osr-web/src/composables/__tests__/usePtSubscription.spec.ts)：30 个测试通过，包含 candidateId 与副标题回传；前端类型检查和生产构建成功。

以新后端 jar 在独立端口 16895 启动，健康接口返回 HTTP 200、`status=UP, db=UP`。临时实例已停止；验证期间临时启动的本地测试数据库恢复为原先停止状态。

## 保留的边界与后续工作

后续季的资源年份可能是本季播出年，因此继续允许晚于作品首播年，避免把长寿剧的新季当错资源。完全缺少 ID、副标题或其他消歧证据的单个英文重名资源，仍无法凭空确定真实作品；本次增加明确反证和多订阅歧义保护，没有接入同步逐条 TMDb/AI 查询。

无法解析集号的视频保留并维持“尚未确认”，避免把未知当作不含目标集。已完成的错季种子只释放错误占位，不自动删可能负有保种义务的文件。

分析报告中的 RSS 分页/断档补救、展示被拒绝候选、持久化推送意图与跨入口配额属于后续优化，本次未一并引入。已有错下记录应按实际订阅、种子与入库状态核对后处理，补丁不会自动清理它们。
