package com.osr.openliststrm.wecom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 企业微信回调报文的签名校验与 AES 加解密（协议见企微「加解密方案说明」）。
 * <p>
 * 纯函数式工具类，不持有任何配置、不碰 Spring：密钥每次由调用方传入，
 * 因此后台改了 Token/AESKey 立刻生效，也让本类可以直接单测。
 * <p>
 * 协议要点（写下来是因为这几处一旦记错，表现都是「解密出乱码」而非明确报错）：
 * <ul>
 *   <li>AESKey = Base64Decode(EncodingAESKey + "=")，固定 32 字节；IV 取其前 16 字节。</li>
 *   <li>加密算法是 AES/CBC/<b>NoPadding</b>，补位由本类自己按 PKCS#7 做——
 *       JDK 的 PKCS5Padding 块长固定 8 字节，直接用会补错。</li>
 *   <li>明文结构：16 字节随机数 + 4 字节网络序长度 + 消息体 + receiveid(corpid)。</li>
 *   <li>签名 = SHA1(token/timestamp/nonce/encrypt 四者<b>字典序排序后</b>直接拼接)。</li>
 * </ul>
 *
 * @author Jack
 */
public final class WeComCrypto {

    /** 企微固定的分组长度，PKCS#7 补位按此对齐 */
    private static final int BLOCK_SIZE = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WeComCrypto() {
    }

    /**
     * 校验企微回调签名。
     *
     * @param token       后台配置的回调 Token
     * @param signature   企微传来的 msg_signature
     * @param timestamp   企微传来的 timestamp
     * @param nonce       企微传来的 nonce
     * @param encrypt     密文（GET 验证时是 echostr，POST 时是报文里的 Encrypt 节点）
     * @return 签名是否匹配
     */
    public static boolean verifySignature(String token, String signature, String timestamp, String nonce, String encrypt) {
        if (token == null || signature == null) {
            return false;
        }
        String expected = sha1(token, timestamp, nonce, encrypt);
        // 定长十六进制串，用常量时间比较避免签名被逐字节试探
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA1(四个参数按字典序排序后拼接)，输出小写十六进制 */
    static String sha1(String... params) {
        String[] sorted = params.clone();
        Arrays.sort(sorted);
        StringBuilder joined = new StringBuilder();
        for (String param : sorted) {
            joined.append(param == null ? "" : param);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-1 失败", e);
        }
    }

    /**
     * 解密企微密文，返回其中的消息体明文。
     *
     * @param encodingAesKey 后台配置的 EncodingAESKey（43 位）
     * @param corpId         企业 ID，用于校验报文归属（企微把它编在明文尾部）
     * @param encrypted      Base64 密文
     * @throws IllegalArgumentException 密钥非法、密文损坏或 receiveid 与本企业不符
     */
    public static String decrypt(String encodingAesKey, String corpId, String encrypted) {
        byte[] aesKey = parseAesKey(encodingAesKey);
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(Arrays.copyOf(aesKey, 16)));
            plain = pkcs7Unpad(cipher.doFinal(Base64.getDecoder().decode(encrypted)));
        } catch (Exception e) {
            throw new IllegalArgumentException("企微回调报文解密失败，请检查 EncodingAESKey 是否配置正确", e);
        }
        // 16 字节随机数 + 4 字节长度 + 消息体 + receiveid
        if (plain.length < 20) {
            throw new IllegalArgumentException("企微回调报文解密后长度异常");
        }
        int contentLength = ByteBuffer.wrap(plain, 16, 4).getInt();
        if (contentLength < 0 || 20 + contentLength > plain.length) {
            throw new IllegalArgumentException("企微回调报文长度字段非法");
        }
        String content = new String(plain, 20, contentLength, StandardCharsets.UTF_8);
        String receiveId = new String(plain, 20 + contentLength, plain.length - 20 - contentLength, StandardCharsets.UTF_8);
        if (corpId != null && !corpId.isBlank() && !corpId.equals(receiveId)) {
            throw new IllegalArgumentException("企微回调报文的 receiveid 与本企业 corpid 不符");
        }
        return content;
    }

    /**
     * 加密消息体，供被动回复报文使用。当前实现走异步主动推送，本方法暂无生产调用点，
     * 但加解密是成对的协议实现，缺一半会让后续想改回被动回复的人重新踩一遍补位的坑。
     */
    public static String encrypt(String encodingAesKey, String corpId, String content) {
        byte[] aesKey = parseAesKey(encodingAesKey);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] corpBytes = corpId.getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);

        byte[] plain = ByteBuffer.allocate(16 + 4 + contentBytes.length + corpBytes.length)
                .put(random)
                .putInt(contentBytes.length)
                .put(contentBytes)
                .put(corpBytes)
                .array();
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(Arrays.copyOf(aesKey, 16)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(pkcs7Pad(plain)));
        } catch (Exception e) {
            throw new IllegalArgumentException("企微报文加密失败", e);
        }
    }

    /** EncodingAESKey 是 43 位 Base64（去掉了末尾的 '='），补回来才能解出 32 字节密钥 */
    private static byte[] parseAesKey(String encodingAesKey) {
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("EncodingAESKey 必须是 43 位字符串");
        }
        byte[] key = Base64.getDecoder().decode(encodingAesKey + "=");
        if (key.length != 32) {
            throw new IllegalArgumentException("EncodingAESKey 解码后长度不是 32 字节");
        }
        return key;
    }

    private static byte[] pkcs7Pad(byte[] source) {
        int padLength = BLOCK_SIZE - (source.length % BLOCK_SIZE);
        byte[] padded = Arrays.copyOf(source, source.length + padLength);
        Arrays.fill(padded, source.length, padded.length, (byte) padLength);
        return padded;
    }

    private static byte[] pkcs7Unpad(byte[] decrypted) {
        if (decrypted.length == 0) {
            return decrypted;
        }
        int padLength = decrypted[decrypted.length - 1] & 0xFF;
        if (padLength < 1 || padLength > BLOCK_SIZE || padLength > decrypted.length) {
            // 补位字节非法说明密钥不对：此时解出来的是随机字节，继续往下走只会得到乱码明文
            throw new IllegalArgumentException("企微回调报文补位字节非法，EncodingAESKey 可能不匹配");
        }
        return Arrays.copyOf(decrypted, decrypted.length - padLength);
    }
}
