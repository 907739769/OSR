package com.osr.openliststrm.enums;

/**
 * PT 下载器的分工。
 * <p>
 * 订阅推送在没有显式指定下载器时会在启用的下载器之间做负载均衡，把「只做种的下载器」
 * 一并算进池子就会出现"订阅的种子被推到保种机上"。这个枚举是两者的唯一分界。
 * </p>
 *
 * @author Jack
 */
public enum PtDownloaderRoleEnum {

    /** 参与订阅下载：进负载均衡池，订阅可以显式指定它 */
    DOWNLOAD("DOWNLOAD", "订阅下载"),

    /**
     * 仅做种：接收 IYUU 转移/辅种过来的种子，不参与订阅下载。
     * 自动删种通常只在这类下载器上开启。
     */
    SEED_ONLY("SEED_ONLY", "仅做种");

    private final String code;
    private final String desc;

    PtDownloaderRoleEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 存量行的 role 为 null/空白（列是后加的），一律按 DOWNLOAD 处理——
     * 升级前只有一个下载器且它确实在下订阅，退化成 DOWNLOAD 才能让升级前后行为一致。
     */
    public static PtDownloaderRoleEnum getByCode(String code) {
        for (PtDownloaderRoleEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return DOWNLOAD;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
