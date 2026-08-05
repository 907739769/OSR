package com.osr.openliststrm.wecom;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企微回调加解密与签名校验。
 * <p>
 * 这套协议出错时的表现都是「解出乱码」而不是明确报错，所以这里把每个容易记错的点
 * 都钉一条用例：补位块长是 32 不是 8、IV 取密钥前 16 字节、签名要先排序再拼接、
 * receiveid 必须比对。
 */
class WeComCryptoTest {

    /** 43 位 EncodingAESKey，补一个 '=' 后 Base64 解出 32 字节 */
    private static final String AES_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
            .substring(0, 43);

    private static final String CORP_ID = "ww1234567890abcdef";
    private static final String TOKEN = "myCallbackToken";

    @Test
    void 加密再解密_还原原文() {
        String plain = "<xml><Content><![CDATA[订阅 三体]]></Content></xml>";

        String encrypted = WeComCrypto.encrypt(AES_KEY, CORP_ID, plain);

        assertEquals(plain, WeComCrypto.decrypt(AES_KEY, CORP_ID, encrypted));
    }

    /** 明文长度跨越 32 字节分组边界的各种情形，补位算错时这里最先炸 */
    @Test
    void 加解密_覆盖各种长度_均能还原() {
        for (int length : new int[]{0, 1, 11, 31, 32, 33, 64, 200}) {
            String plain = "x".repeat(length);
            String encrypted = WeComCrypto.encrypt(AES_KEY, CORP_ID, plain);
            assertEquals(plain, WeComCrypto.decrypt(AES_KEY, CORP_ID, encrypted), "长度 " + length + " 还原失败");
        }
    }

    /** 中文按 UTF-8 是多字节，长度字段写的是字节数而非字符数 */
    @Test
    void 加解密_中文原文_长度字段按字节计算() {
        String plain = "订阅命中：《三体》第 1 季第 3 集";

        assertEquals(plain, WeComCrypto.decrypt(AES_KEY, CORP_ID, WeComCrypto.encrypt(AES_KEY, CORP_ID, plain)));
    }

    @Test
    void 解密_receiveid与本企业不符_拒绝() {
        String encrypted = WeComCrypto.encrypt(AES_KEY, "ww_other_corp", "hello");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WeComCrypto.decrypt(AES_KEY, CORP_ID, encrypted));
        assertTrue(e.getMessage().contains("receiveid"));
    }

    @Test
    void 解密_密钥不匹配_报补位非法而不是返回乱码() {
        String otherKey = Base64.getEncoder()
                .encodeToString("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8))
                .substring(0, 43);
        String encrypted = WeComCrypto.encrypt(AES_KEY, CORP_ID, "hello");

        assertThrows(IllegalArgumentException.class, () -> WeComCrypto.decrypt(otherKey, CORP_ID, encrypted));
    }

    @Test
    void 解密_AESKey长度不是43位_直接拒绝() {
        assertThrows(IllegalArgumentException.class, () -> WeComCrypto.decrypt("tooShort", CORP_ID, "whatever"));
    }

    /** 签名是四个值排序后拼接的 SHA1，与参数传入顺序无关 */
    @Test
    void 签名校验_与参数拼接顺序无关() {
        String expected = WeComCrypto.sha1(TOKEN, "1700000000", "nonce123", "cipher");

        assertTrue(WeComCrypto.verifySignature(TOKEN, expected, "1700000000", "nonce123", "cipher"));
    }

    @Test
    void 签名校验_签名不符_返回false() {
        assertFalse(WeComCrypto.verifySignature(TOKEN, "deadbeef", "1700000000", "nonce123", "cipher"));
    }

    @Test
    void 签名校验_Token不符_返回false() {
        String signature = WeComCrypto.sha1("anotherToken", "1700000000", "nonce123", "cipher");

        assertFalse(WeComCrypto.verifySignature(TOKEN, signature, "1700000000", "nonce123", "cipher"));
    }
}
