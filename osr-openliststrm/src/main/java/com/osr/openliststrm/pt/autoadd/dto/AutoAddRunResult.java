package com.osr.openliststrm.pt.autoadd.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一轮热门自动订阅规则的执行结果统计。
 *
 * @author Jack
 */
@Data
@AllArgsConstructor
public class AutoAddRunResult {

    /** 新增订阅数 */
    private int addedCount;

    /** 因过滤/已存在跳过数 */
    private int skippedCount;

    /** 建订阅失败数 */
    private int failedCount;
}
