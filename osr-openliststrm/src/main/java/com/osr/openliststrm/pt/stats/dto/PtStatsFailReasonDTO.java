package com.osr.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * 失败原因分布：按 {@code pt_download_record.fail_reason_code} 聚合，
 * {@code reason} 是该码的中文短标签（{@code FailReasonCode#labelOf}）。
 * <p>
 * 与搜索侧的 {@code PtStatsRejectReasonDTO} 同构。<b>不按 fail_reason 原文聚合</b>——
 * 那一列的文案里嵌着集号与超时小时数，按它分组只会得到一堆计数为 1 的碎片。
 * </p>
 *
 * @author Jack
 */
@Data
public class PtStatsFailReasonDTO {

    /** 原始码，见后端 FailReasonCode 枚举；历史未分类记录归为 OTHER */
    private String code;

    /** 中文短标签，如「下载超时」 */
    private String reason;

    private long count;
}
