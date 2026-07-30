package com.ruoyi.common.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感配置（PT 下载器密码、索引器 apikey 等）落库前的对称加密工具。
 * <p>
 * 密钥来自 {@code openliststrm.security.credential-secret}（对应环境变量 PT_CREDENTIAL_SECRET）。
 * 未配置时使用固定的内置兜底密钥并打警告日志——兜底密钥是编译期常量，只能防止明文落库，
 * 不具备真正的机密性，生产环境必须显式配置。兜底密钥必须是固定值而非随机生成：
 * 若像 JwtConfigProperties 那样每次启动生成随机密钥，服务重启后所有已加密的历史数据将永久无法解密。
 * <p>
 * 密文格式：{@code ENC:Base64(iv + ciphertext+tag)}。不带 "ENC:" 前缀的值被当作历史遗留明文直接返回，
 * 兼容升级前已入库的明文数据，下次保存时会被自动加密。
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String PREFIX = "ENC:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    // 仅作最后兜底，不具备机密性；生产环境请通过 PT_CREDENTIAL_SECRET 显式配置
    private static final String FALLBACK_SECRET = "openliststrm-pt-credential-fallback-key-please-override";

    private static volatile SecretKeySpec KEY;

    @Value("${openliststrm.security.credential-secret:}")
    private String configuredSecret;

    @PostConstruct
    public void init() {
        String secret = StringUtils.isNotBlank(configuredSecret) ? configuredSecret : FALLBACK_SECRET;
        if (StringUtils.isBlank(configuredSecret)) {
            log.warn("[Credential] openliststrm.security.credential-secret 未配置，已使用内置兜底密钥加密 PT 下载器密码/索引器 apikey，"
                    + "不具备真正机密性。请在环境变量中设置 PT_CREDENTIAL_SECRET。");
        }
        KEY = deriveKey(secret);
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化敏感配置加密密钥失败", e);
        }
    }

    public static String encrypt(String plain) {
        if (StringUtils.isBlank(plain)) {
            return plain;
        }
        requireKey();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密敏感配置失败", e);
        }
    }

    public static String decrypt(String stored) {
        if (StringUtils.isBlank(stored) || !stored.startsWith(PREFIX)) {
            // 历史遗留明文数据，直接原样返回
            return stored;
        }
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密敏感配置失败，密钥可能已变更", e);
        }
    }

    private static void requireKey() {
        if (KEY == null) {
            // 兜底：单测等未走 Spring 容器场景下懒加载兜底密钥，保证工具类可独立使用
            synchronized (CredentialCipher.class) {
                if (KEY == null) {
                    KEY = deriveKey(FALLBACK_SECRET);
                }
            }
        }
    }
}
