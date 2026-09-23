package com.osr.openliststrm.req;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 下载记录列表 / 统计条的查询条件。
 * <p>
 * {@code params} 装推送时间区间（{@code params[beginTime]} / {@code params[endTime]}），
 * 写法与其余记录页一致，由 {@code useRecordList} 的日期区间写入；
 * 必须预先 new 出来，Spring 才能把 {@code params[xxx]} 绑定进去。
 * </p>
 *
 * @author Jack
 */
@Data
public class PtDownloadRecordQueryReq {

    private Integer subId;
    private String state;
    /** 按种子标题模糊匹配 */
    private String title;
    /** 失败原因分类 TORRENT_NOT_FOUND / ZOMBIE_TIMEOUT / NO_TARGET_EPISODE / METADATA_TIMEOUT / OTHER */
    private String failReasonCode;
    private Integer indexerId;
    private Integer downloaderId;
    /** H&R 保种状态 PENDING / SATISFIED / VIOLATED */
    private String hrState;
    /**
     * 为 true 时隐藏「已被后续推送接替」的失败记录，其余状态不受影响。
     * 统计条的失败数随之只剩还没着落的那些，与统计仪表盘的失败数同一口径。
     */
    private Boolean hideSuperseded;
    /**
     * 日期区间落在哪一列：PUSHED（默认，推送时间）/ COMPLETED（完成时间）/ FAILED（失败时间，即 FAILED 行的 update_time）。
     * 统计仪表盘的趋势图三条线各按自己的日期列分组，从图上点进来时必须按同一列筛，否则条数对不上。
     */
    private String dateField;

    private Map<String, Object> params = new HashMap<>();
}
