package com.osr.openliststrm.pt.indexer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GuidHasher 单元测试。
 *
 * @author Jack
 */
class GuidHasherTest {

    @Test
    void hash_已知输入_产出已知SHA256十六进制() {
        // 标准 SHA-256("abc") 测试向量
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                GuidHasher.hash("abc"));
    }

    @Test
    void hash_输出恒为64位小写十六进制() {
        String result = GuidHasher.hash("https://rousi.pro/api/torrent/xxx/download/yyy");

        assertEquals(64, result.length());
        assertEquals(result.toLowerCase(), result);
        assertTrue(result.matches("[0-9a-f]{64}"));
    }

    @Test
    void hash_哈希结果首字节小于16_前导零不丢失() {
        // "guid-test-19" 的 SHA-256 首字节为 0x0f，若实现用会吃掉前导零的格式化方式
        // （如 Integer.toHexString），输出会变成 63 位且以 "f" 开头而非 "0f"
        String result = GuidHasher.hash("guid-test-19");

        assertEquals(64, result.length());
        assertEquals("0f85b83bc0db41996b507b13cd1253e496d9eea1fad8e164a3d365457db3af89", result);
        assertTrue(result.startsWith("0f"));
    }

    @Test
    void hash_相同输入两次调用结果一致() {
        String guid = "https://pt.example.com/details.php?id=1";

        assertEquals(GuidHasher.hash(guid), GuidHasher.hash(guid));
    }

    @Test
    void hash_null输入_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> GuidHasher.hash(null));
    }

    @Test
    void hash_空白输入_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> GuidHasher.hash("   "));
        assertThrows(IllegalArgumentException.class, () -> GuidHasher.hash(""));
    }
}
