package com.osr.openliststrm.pt.indexer;

import com.osr.common.utils.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 计算 {@link com.osr.openliststrm.pt.model.TorrentInfo#getGuid()} 的 SHA-256 哈希值。
 * <p>
 * guid（RSS 条目唯一标识、或降级后的下载地址）本身是 URL，长度不定且可能很长，
 * 无法直接建唯一索引，因此下载记录表 pt_download_record 用
 * (indexer_id, guid_hash) 做去重键，guid_hash 即本类的输出。
 * </p>
 *
 * @author Jack
 */
public final class GuidHasher {

    private static final String ALGORITHM = "SHA-256";

    private GuidHasher() {
    }

    /**
     * 计算 guid 的 SHA-256 十六进制摘要。
     *
     * @param guid 待哈希的 guid 原文，不能为 null 或空白
     * @return 固定 64 位小写十六进制字符串
     * @throws IllegalArgumentException guid 为 null 或空白——拿不到 guid 是上游的问题，
     *                                   不应静默产出一个假哈希掩盖问题
     */
    public static String hash(String guid) {
        if (StringUtils.isBlank(guid)) {
            throw new IllegalArgumentException("guid不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            // 显式指定 UTF-8，避免同一个 guid 在不同平台默认编码下算出不同的 hash
            byte[] hashBytes = digest.digest(guid.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                // 用 %02x 保证每个字节固定输出两位，前导零不会被吃掉
                // （Integer.toHexString 等写法遇到 <0x10 的字节会漏掉前导零，是这类工具最经典的 bug）
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 保证支持的标准算法，理论上不会走到这里
            throw new IllegalStateException("当前JVM不支持SHA-256算法", e);
        }
    }
}
