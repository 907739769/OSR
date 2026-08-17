package com.osr.openliststrm.pt.health.dto;

/**
 * 体检结果里的一集。
 *
 * @param episode     集号（电影恒为 0）
 * @param state       集状态 MISSING/IN_FLIGHT/BLOCKED（UPGRADING 不纳入体检——那一集本来就在库里）
 * @param airDate     播出日期 yyyy-MM-dd，无日期时为 null
 * @param overdueDays 已播出天数（今天 − airDate），无日期时为 null 而不是 0——
 *                    0 表示"今天刚播"，与"算不出来"是两回事，混用会让前端排序把无日期的排到最前
 * @param bucket      所属分档，取值见 {@code EpisodeHealthBucket}
 * @param diagnosis   诊断码，取值见 {@code EpisodeHealthDiagnosis}
 *
 * @author Jack
 */
public record EpisodeHealthItem(Integer episode, String state, String airDate, Integer overdueDays,
                                String bucket, String diagnosis) {
}
