package com.osr.openliststrm.service;

/**
 * 批量删除网盘文件的结果。
 * <p>
 * 超过 {@link #BACKGROUND_THRESHOLD} 条时在后台执行、接口立即返回——此前前端对两种情况一律提示
 * 「删除网盘文件成功」并刷新列表，后台模式下刷新出来记录都还在，读起来像删除没生效；
 * 同步模式下个别文件删除失败（记录会保留）也看不出来。
 *
 * @param requested  选中的记录数
 * @param removed    实际删除成功的文件数；后台执行时返回时还不知道，为 0
 * @param background 是否转到后台执行
 */
public record BatchRemoveOutcome(int requested, int removed, boolean background) {

    /** 超过这个条数转后台执行，与按记录重试同一阈值 */
    public static final int BACKGROUND_THRESHOLD = 20;

    public static BatchRemoveOutcome inBackground(int requested) {
        return new BatchRemoveOutcome(requested, 0, true);
    }
}
