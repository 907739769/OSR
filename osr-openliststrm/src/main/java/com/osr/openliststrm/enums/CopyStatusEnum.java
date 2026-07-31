package com.osr.openliststrm.enums;

/**
 * @Author Jack
 * @Date 2025/7/29 18:44
 * @Version 1.0.0
 */
public enum CopyStatusEnum {
    PROCESSING("1", "处理中"),
    FAILED("2", "失败"),
    SUCCESS("3", "成功"),
    UNKNOWN("4", "未知");

    private final String code;
    private final String desc;

    CopyStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    // 根据code获取枚举实例
    public static CopyStatusEnum getByCode(String code) {
        for (CopyStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    // 根据code获取描述
    public static String getDescByCode(String code) {
        CopyStatusEnum status = getByCode(code);
        return status != null ? status.desc : "其他状态";
    }

    // Getter方法
    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
