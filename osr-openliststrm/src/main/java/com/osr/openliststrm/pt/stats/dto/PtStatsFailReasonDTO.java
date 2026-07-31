package com.osr.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * 失败原因分布：reason 就是 pt_download_record.fail_reason 原始字符串，
 * 本设计口径下只有两种固定文案，不做归一化/正则分桶。
 *
 * @author Jack
 */
@Data
public class PtStatsFailReasonDTO {

    private String reason;

    private long count;
}
