package com.osr.openliststrm.controller.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 下载记录列表的日期区间列。从统计仪表盘的趋势图点进来时，完成与失败两条线必须按各自的日期列筛，
 * 否则点「某天失败 3」进来看到的是那天推送的记录，条数对不上。列名是白名单映射，不接受任意字符串。
 *
 * @author Jack
 */
class PtDownloadRecordDateColumnTest {

    @Test
    void 按取值映射到各自的日期列() {
        assertEquals("pushed_time", PtDownloadRecordRestController.dateColumn(null));
        assertEquals("pushed_time", PtDownloadRecordRestController.dateColumn("PUSHED"));
        assertEquals("completed_time", PtDownloadRecordRestController.dateColumn("completed"));
        assertEquals("update_time", PtDownloadRecordRestController.dateColumn("FAILED"));
    }

    @Test
    void 未知取值回退推送时间_不拼进SQL() {
        assertEquals("pushed_time", PtDownloadRecordRestController.dateColumn("id; drop table x"));
    }
}
