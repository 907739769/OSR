package com.osr.openliststrm.pt.health.dto;

import java.util.List;

/**
 * 体检结果里的一条订阅，携带它名下所有「有问题的集」。
 * <p>
 * 按订阅聚合而不是平铺成集列表：一个季包能一次放出几十集，平铺后一部剧就占满整页，
 * 用户读不出「一共几部剧有问题」——而那正是他打开这个页面要问的第一个问题。
 * </p>
 *
 * @param subId          订阅ID
 * @param tmdbId         TMDb ID
 * @param title          作品标题
 * @param posterPath     TMDb 海报相对路径
 * @param mediaType      媒体类型。体检不含电影，因此恒为 TV——保留该字段是为了让前端
 *                       不必假设这一点（口径变了页面不用跟着改）
 * @param season         季号（特别篇为 0）
 * @param autoSearch     是否已开启自动补搜，前端据此决定要不要给「一键开启」按钮
 * @param lastSearchTime 上次发起补搜的时间 yyyy-MM-dd HH:mm:ss，从未搜过为 null
 * @param missStreak     连续落空轮数，0 表示上轮有命中或还没跑过
 * @param rejectDetail   上次落空的淘汰原因（已翻译成中文标签），无则为 null
 * @param maxOverdueDays 名下最大已播出天数，列表按它倒序；全都没有日期时为 null
 * @param diagnoses      去重后的诊断码，按 {@code EpisodeHealthDiagnosis} 的声明顺序
 * @param buckets        去重后的分档码，按 {@code EpisodeHealthBucket} 的声明顺序
 * @param ignored        用户是否已把这条订阅从体检里忽略掉。忽略的默认不出现在报告里，
 *                       只有前端显式要求包含时才回传，此时靠这个字段渲染「已忽略」标记
 * @param episodes       有问题的集，按集号升序
 *
 * @author Jack
 */
public record SubscriptionHealthItem(Integer subId, String tmdbId, String title, String posterPath,
                                     String mediaType, Integer season, boolean autoSearch,
                                     String lastSearchTime, Integer missStreak, String rejectDetail,
                                     Integer maxOverdueDays, List<String> diagnoses, List<String> buckets,
                                     boolean ignored, List<EpisodeHealthItem> episodes) {
}
