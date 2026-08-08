package com.osr.openliststrm.controller.api;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 列表查询里 {@code params.beginTime / params.endTime} 的取值归一化。
 * <p>
 * 这两个值由前端拼接（日期 + 固定时分秒）后原样进入 SQL 与 DATETIME 列比较，
 * 一旦拼出半截值（例如只填了开始日期却仍拼出 {@code " 23:59:59"}），MySQL 会直接抛
 * {@code Incorrect DATETIME value} —— 一个搜索条件写错就变成 500。
 * 这里在入库前把不成形的值挡掉：宁可忽略这一侧的时间过滤，也不要整个列表查不出来。
 * </p>
 *
 * @author Jack
 */
public final class QueryTimeRange
{
    /** 允许 {@code yyyy-MM-dd} 与 {@code yyyy-MM-dd HH:mm:ss} 两种形状，前端只会产出这两种 */
    private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2})?");

    private QueryTimeRange()
    {
    }

    /**
     * 从 params 中取出时间条件并校验格式。
     *
     * @param params 查询实体上的 params（可能为 null）
     * @param key    beginTime / endTime
     * @return 合法的时间字符串；缺失、空串或格式不合法时返回 null（调用方据此跳过该条件）
     */
    public static String get(Map<String, Object> params, String key)
    {
        if (params == null)
        {
            return null;
        }
        Object raw = params.get(key);
        if (raw == null)
        {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty() || !DATE_TIME.matcher(value).matches())
        {
            return null;
        }
        return value;
    }
}
