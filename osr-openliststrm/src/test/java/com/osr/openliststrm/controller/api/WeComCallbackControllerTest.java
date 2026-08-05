package com.osr.openliststrm.controller.api;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.wecom.WeComApiClient;
import com.osr.openliststrm.wecom.WeComCommandService;
import com.osr.openliststrm.wecom.WeComCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 企微回调 URL 验证（GET）。
 * <p>
 * 企微后台点「保存」时会打这个接口，要求把 echostr 解密后<b>原样返回明文</b>。
 * 返回错了只会在企微后台看到一句「验证失败」，没有任何细节，所以这里把
 * 「什么情况该返回明文、什么情况该返回空串」逐条钉死。
 * <p>
 * 加解密本身由 {@code WeComCryptoTest} 覆盖，这里只管控制器这一层的判定与返回。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComCallbackControllerTest {

    private static final String CORP_ID = "ww1234567890abcdef";
    private static final String TOKEN = "myCallbackToken";
    /** 43 位 EncodingAESKey */
    private static final String AES_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
            .substring(0, 43);

    @Mock private OpenlistConfig config;
    @Mock private WeComApiClient apiClient;
    @Mock private WeComCommandService commandService;

    @InjectMocks private WeComCallbackController controller;

    @BeforeEach
    void setUp() {
        when(apiClient.isCallbackConfigured()).thenReturn(true);
        when(config.getWeComToken()).thenReturn(TOKEN);
        when(config.getWeComAesKey()).thenReturn(AES_KEY);
        when(config.getWeComCorpId()).thenReturn(CORP_ID);
    }

    /** 企微验证请求：把明文 echostr 加密，再按协议算出 msg_signature */
    private String[] buildVerifyRequest(String plainEcho) {
        String encrypted = WeComCrypto.encrypt(AES_KEY, CORP_ID, plainEcho);
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String signature = sign(TOKEN, timestamp, nonce, encrypted);
        return new String[]{signature, timestamp, nonce, encrypted};
    }

    /**
     * 按企微协议算签名：四个值字典序排序后拼接取 SHA-1。
     * <p>
     * 这里<b>刻意不复用</b> {@code WeComCrypto} 的实现（它也是包内可见），
     * 由测试独立算一遍——用被测代码自己的实现去验证它自己，排序写反了两边一样错，
     * 测试照样绿。
     */
    private static String sign(String... params) {
        String[] sorted = params.clone();
        java.util.Arrays.sort(sorted);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", sorted).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 验证请求合法_返回解密后的明文() {
        String plain = "1616140317555161061";
        String[] req = buildVerifyRequest(plain);

        String result = controller.verify(req[0], req[1], req[2], req[3]);

        assertEquals(plain, result);
    }

    /** Token 填错是最常见的失误，此时签名对不上，必须拒绝而不是照样解密 */
    @Test
    void Token不一致_签名不匹配_返回空串() {
        String[] req = buildVerifyRequest("1616140317555161061");
        when(config.getWeComToken()).thenReturn("wrongToken");

        assertEquals("", controller.verify(req[0], req[1], req[2], req[3]));
    }

    /** AESKey 填错：签名能过（签名不涉及 AESKey），解密必然失败 */
    @Test
    void AESKey不一致_解密失败_返回空串() {
        String[] req = buildVerifyRequest("1616140317555161061");
        String otherKey = Base64.getEncoder()
                .encodeToString("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8))
                .substring(0, 43);
        when(config.getWeComAesKey()).thenReturn(otherKey);

        assertEquals("", controller.verify(req[0], req[1], req[2], req[3]));
    }

    /** corpid 填的是别的企业：解密得出的 receiveid 对不上，拒绝 */
    @Test
    void corpid不一致_返回空串() {
        String[] req = buildVerifyRequest("1616140317555161061");
        when(config.getWeComCorpId()).thenReturn("ww_other_corp");

        assertEquals("", controller.verify(req[0], req[1], req[2], req[3]));
    }

    /**
     * 配置没填完就去企微点保存 —— 实际踩到的就是这条：
     * 企微生成的 Token/AESKey 要先填回 OSR 保存，再去企微点验证。
     */
    @Test
    void 回调参数未配置完整_返回空串() {
        when(apiClient.isCallbackConfigured()).thenReturn(false);
        String[] req = buildVerifyRequest("1616140317555161061");

        assertEquals("", controller.verify(req[0], req[1], req[2], req[3]));
    }

    /** 明文 echostr 含中文/长内容时也要原样还原（长度字段按字节算） */
    @Test
    void 明文含多字节字符_原样还原() {
        String plain = "验证串-echo-1616140317555161061";
        String[] req = buildVerifyRequest(plain);

        assertEquals(plain, controller.verify(req[0], req[1], req[2], req[3]));
    }
}
