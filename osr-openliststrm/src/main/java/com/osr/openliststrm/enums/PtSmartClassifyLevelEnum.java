package com.osr.openliststrm.enums;

/**
 * PT 下载器保存路径的智能分类级别。
 *
 * @Author Jack
 */
public enum PtSmartClassifyLevelEnum {
    /** 不分类，直接用下载器配置的 save_path */
    NONE("NONE", "不分类"),
    /** 按媒体类型分类：save_path/电影 或 save_path/剧集 */
    CATEGORY("CATEGORY", "按类型分类"),
    /** 按媒体类型 + 首播年份分类：save_path/电影/2024 */
    CATEGORY_YEAR("CATEGORY_YEAR", "按类型+年份分类");

    private final String code;
    private final String desc;

    PtSmartClassifyLevelEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PtSmartClassifyLevelEnum getByCode(String code) {
        for (PtSmartClassifyLevelEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return NONE;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
