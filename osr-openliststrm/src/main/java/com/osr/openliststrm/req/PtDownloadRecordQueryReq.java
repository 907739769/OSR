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

    private Map<String, Object> params = new HashMap<>();
}
