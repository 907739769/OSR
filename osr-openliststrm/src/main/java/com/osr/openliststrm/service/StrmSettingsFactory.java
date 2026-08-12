package com.osr.openliststrm.service;

import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * 把全局 STRM 配置与任务级覆盖合并成一份生效的 {@link StrmSettings}。
 * <p>
 * 覆盖以 JSON 存在 openlist_strm_task.strm_override，<b>只有出现在 JSON 里的键才覆盖</b>，
 * 没出现的沿用全局值——与 pt_subscription.filter_override 同一套约定，见
 * {@code FilterCriteriaFactory}。因此覆盖列为空时，行为与引入该字段之前完全一致。
 * </p>
 * <p>
 * 可覆盖的三项都只影响写侧（生成 STRM 时用一次），没有下游读取方。
 * <b>URL 编码开关与视频/字幕扩展名刻意不在其列</b>：前者有三个解码侧消费者
 * （{@code RenameOrphanScanServiceImpl}、{@code RenameCleanupService}、
 * {@code StrmSourcePathResolver} 都要从 .strm 内容反解回网盘路径），后者是 sys_dict 里的
 * 全站字典；两者都是「播放器/媒体库吃什么」的全局属性，分库配置只会制造解不开的历史数据。
 * </p>
 *
 * @author Jack
 */
@Slf4j
public final class StrmSettingsFactory {

    /** 输出根目录的兜底值，与 sys_config 未配置时的历史行为一致 */
    public static final String DEFAULT_OUTPUT_DIR = "/data/strm";

    public static final String KEY_OUTPUT_DIR = "outputDir";
    public static final String KEY_DOWNLOAD_SUB = "downloadSub";
    /** 单位 MB，与 sys_config 的 openlist.copy.minfilesize 保持一致 */
    public static final String KEY_MIN_FILE_SIZE = "minFileSize";

    private StrmSettingsFactory() {
    }

    /**
     * @param global   全局配置，不可为 null
     * @param override 任务级覆盖 JSON，允许为 null / 空白 / 格式损坏
     */
    public static StrmSettings build(OpenlistConfig global, String override) {
        JSONObject patch = parseOverride(override);

        String outputDir = strOf(patch, KEY_OUTPUT_DIR, global.getOpenListStrmOutputDir());
        // 覆盖值被清成空串时同样退回默认根目录：空的输出目录会让 STRM 直接写到进程工作目录
        if (StringUtils.isBlank(outputDir)) {
            outputDir = DEFAULT_OUTPUT_DIR;
        }

        boolean downloadSub = patch.containsKey(KEY_DOWNLOAD_SUB)
                ? isTruthy(strOf(patch, KEY_DOWNLOAD_SUB, null))
                : "1".equals(global.getOpenListStrmDownloadSub());

        long minSize = patch.containsKey(KEY_MIN_FILE_SIZE)
                ? mbToBytes(patch, global)
                : global.getMinFileSizeBytes();

        return new StrmSettings(outputDir.trim(), downloadSub, minSize);
    }

    /**
     * 覆盖里的体积按 MB 填写（与参数设置页面里的全局项同一单位），这里换算成字节。
     * 负数按 0 处理——「不限」是合法诉求，负的阈值不是。
     */
    private static long mbToBytes(JSONObject patch, OpenlistConfig global) {
        try {
            Long mb = patch.getLong(KEY_MIN_FILE_SIZE);
            if (mb == null) {
                return global.getMinFileSizeBytes();
            }
            return mb <= 0 ? 0L : mb * 1024 * 1024;
        } catch (Exception e) {
            // 典型场景：用户填成 "500MB" 这种带单位的字符串
            log.warn("STRM 任务级覆盖字段 {} 的值 {} 不是合法数字，已回退全局配置：{}",
                    KEY_MIN_FILE_SIZE, patch.get(KEY_MIN_FILE_SIZE), e.getMessage());
            return global.getMinFileSizeBytes();
        }
    }

    /**
     * 开关的真值判定。数据库里存 "0"/"1"，但覆盖 JSON 由前端表单产生，很可能是原生布尔值，
     * 两种都要认——否则「下字幕」会被静默关掉，用户只会觉得字幕丢了。
     */
    private static boolean isTruthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /**
     * 解析覆盖 JSON。为 null/空白、格式非法、或不是 JSON 对象时一律返回空 patch，
     * 使全部字段退回全局配置——一条坏数据不该让整个 STRM 任务跑不起来。
     */
    private static JSONObject parseOverride(String override) {
        if (StringUtils.isBlank(override)) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSONObject.parseObject(override);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception e) {
            log.warn("STRM 任务级覆盖不是合法的 JSON 对象，已整体退回全局配置：{}", e.getMessage());
            return new JSONObject();
        }
    }

    private static String strOf(JSONObject patch, String key, String fallback) {
        if (!patch.containsKey(key)) {
            return fallback;
        }
        try {
            return patch.getString(key);
        } catch (Exception e) {
            log.warn("STRM 任务级覆盖字段 {} 的值 {} 无法解析为字符串，已回退全局配置：{}",
                    key, patch.get(key), e.getMessage());
            return fallback;
        }
    }
}
