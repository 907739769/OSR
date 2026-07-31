package com.osr.openliststrm.enums;

/**
 * @Author Jack
 * @Date 2025/7/29 18:44
 * @Version 1.0.0
 */
public enum StrmStatusEnum {
    FAILED("0", "失败"),
    SUCCESS("1", "成功");

    private final String code;
    private final String desc;

    StrmStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    // 根据code获取枚举实例
    public static StrmStatusEnum getByCode(String code) {
        for (StrmStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    // 根据code获取描述
    public static String getDescByCode(String code) {
        StrmStatusEnum status = getByCode(code);
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
