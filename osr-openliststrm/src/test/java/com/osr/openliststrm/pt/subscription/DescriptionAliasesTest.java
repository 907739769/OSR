package com.osr.openliststrm.pt.subscription;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptionAliasesTest {

    /** 青蛙站的真实条目，本类就是为它写的 */
    private static final String QINGWA = "Re：从零开始的异世界生活 / Re:ゼロから始める異世界生活 / "
            + "Re:Zero kara Hajimeru Isekai Seikatsu / Re:ZERO -Starting Life in Another World- "
            + "| S01E51-E66 | 内封简繁字幕";

    @Test
    void 真实条目_取出四个别名() {
        assertEquals(List.of(
                "Re：从零开始的异世界生活",
                "Re:ゼロから始める異世界生活",
                "Re:Zero kara Hajimeru Isekai Seikatsu",
                "Re:ZERO -Starting Life in Another World-"), DescriptionAliases.parse(QINGWA));
    }

    @Test
    void 只取第一段_题材串不会被当成别名() {
        // 「类型: 剧情/奇幻/冒险」同样用 / 分隔，整串切开会产出一批题材词
        List<String> aliases = DescriptionAliases.parse(
                "九门 / 老九门2 / 老九门贰 | 第21集 | 4K 高码 杜比视界 | 类型: 剧情/奇幻/冒险");

        assertEquals(List.of("九门", "老九门2", "老九门贰"), aliases);
    }

    @Test
    void 换行同样是段边界() {
        assertEquals(List.of("九门", "老九门2"),
                DescriptionAliases.parse("九门 / 老九门2\n剧情简介：民国年间/长沙城里"));
    }

    @Test
    void 空与空白一律返回空列表() {
        assertTrue(DescriptionAliases.parse(null).isEmpty());
        assertTrue(DescriptionAliases.parse("   ").isEmpty());
        assertTrue(DescriptionAliases.parse(" | 第3集").isEmpty());
    }

    @Test
    void 剧情简介_有句读的段整条丢弃() {
        List<String> aliases = DescriptionAliases.parse("少年在异世界醒来，发现自己拥有死亡回归的能力");

        assertTrue(aliases.isEmpty(), "带中文句读的必然是句子不是作品名");
    }

    @Test
    void 无分隔符的长简介_被段长上限挡下() {
        String longText = "少年在异世界醒来发现自己拥有死亡回归的能力".repeat(20);

        assertTrue(DescriptionAliases.parse(longText).isEmpty());
    }

    @Test
    void 切出过多段_整段放弃而不是截断取前几个() {
        String many = "a1/a2/a3/a4/a5/a6/a7/a8/a9/a10/a11";

        assertTrue(DescriptionAliases.parse(many).isEmpty(),
                "十几段是『这不是别名列表』的信号，取前 N 个只是把噪声换成更少的噪声");
    }

    @Test
    void 单字别名丢弃_撞上另一部作品的概率远大于收益() {
        assertEquals(List.of("老九门"), DescriptionAliases.parse("老九门 / 门"));
    }

    @Test
    void 英文片名里的半角标点不算句读() {
        assertEquals(List.of("Crazy, Stupid, Love", "Dr. No"),
                DescriptionAliases.parse("Crazy, Stupid, Love / Dr. No | 1080p"));
    }

    @Test
    void 全角分隔符同样支持() {
        assertEquals(List.of("九门", "老九门2"), DescriptionAliases.parse("九门／老九门2｜第21集"));
    }
}
