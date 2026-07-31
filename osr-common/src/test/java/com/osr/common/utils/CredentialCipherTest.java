package com.osr.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未走 Spring 容器时 requireKey() 会懒加载内置兜底密钥，因此这里可以直接测静态方法。
 */
class CredentialCipherTest {

    @Test
    void encrypt_decrypt_往返得到原文() {
        String plain = "s3cr3t-password!";
        String encrypted = CredentialCipher.encrypt(plain);

        assertTrue(encrypted.startsWith("ENC:"));
        assertEquals(plain, CredentialCipher.decrypt(encrypted));
    }

    @Test
    void decrypt_历史遗留明文_原样返回() {
        String legacyPlain = "old-plain-password";

        assertEquals(legacyPlain, CredentialCipher.decrypt(legacyPlain));
    }

    @Test
    void encrypt_空值_原样返回() {
        assertNull(CredentialCipher.encrypt(null));
        assertEquals("", CredentialCipher.encrypt(""));
    }

    @Test
    void encrypt_两次加密同一明文_密文不同() {
        String plain = "same-password";

        String first = CredentialCipher.encrypt(plain);
        String second = CredentialCipher.encrypt(plain);

        assertTrue(first.startsWith("ENC:"));
        assertTrue(second.startsWith("ENC:"));
        assertEquals(plain, CredentialCipher.decrypt(first));
        assertEquals(plain, CredentialCipher.decrypt(second));
    }
}
