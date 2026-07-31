package com.osr.openliststrm.pt.indexer;

import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorznabParserTest {

    private String wrap(String items) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
                  <channel>
                    <title>Indexer</title>
                %s
                  </channel>
                </rss>
                """.formatted(items);
    }

    @Test
    void parse_标准条目_全部字段正确() {
        String xml = wrap("""
                    <item>
                      <title>Some.Show.S01E05.1080p.WEB-DL.H264-GROUP</title>
                      <guid>https://pt.example.com/details.php?id=1</guid>
                      <link>https://pt.example.com/download.php?id=1</link>
                      <pubDate>Mon, 20 Jul 2026 10:00:00 +0800</pubDate>
                      <size>2147483648</size>
                      <torznab:attr name="seeders" value="12"/>
                      <torznab:attr name="peers" value="15"/>
                      <torznab:attr name="infohash" value="ABCDEF0123456789"/>
                      <torznab:attr name="downloadvolumefactor" value="0"/>
                    </item>
                """);

        List<TorrentInfo> list = TorznabParser.parse(xml);

        assertEquals(1, list.size());
        TorrentInfo t = list.get(0);
        assertEquals("Some.Show.S01E05.1080p.WEB-DL.H264-GROUP", t.getTitle());
        assertEquals("https://pt.example.com/download.php?id=1", t.getDownloadUrl());
        assertEquals(2147483648L, t.getSize());
        assertEquals(12, t.getSeeders());
        assertEquals(15, t.getPeers());
        assertEquals("ABCDEF0123456789", t.getInfoHash());
        assertEquals("Mon, 20 Jul 2026 10:00:00 +0800", t.getPubDate());
        assertTrue(t.isFree());
        assertEquals("https://pt.example.com/details.php?id=1", t.getGuid());
    }

    @Test
    void parse_条目缺少guid_回退使用下载地址且不丢弃条目() {
        String xml = wrap("""
                    <item>
                      <title>Movie.2026.1080p.WEB-DL</title>
                      <link>https://pt.example.com/download.php?id=10</link>
                      <size>1073741824</size>
                    </item>
                """);

        List<TorrentInfo> list = TorznabParser.parse(xml);

        assertEquals(1, list.size());
        assertEquals("https://pt.example.com/download.php?id=10", list.get(0).getGuid());
    }

    @Test
    void parse_中文标题_不乱码() {
        String xml = wrap("""
                    <item>
                      <title>大明王朝1566.S01E12.2160p.WEB-DL</title>
                      <guid>https://pt.example.com/details.php?id=2</guid>
                      <link>https://pt.example.com/download.php?id=2</link>
                      <size>5368709120</size>
                    </item>
                """);

        List<TorrentInfo> list = TorznabParser.parse(xml);

        assertEquals("大明王朝1566.S01E12.2160p.WEB-DL", list.get(0).getTitle());
        assertEquals("https://pt.example.com/details.php?id=2", list.get(0).getGuid());
    }

    @Test
    void parse_newznab命名空间_属性同样被识别() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">
                  <channel>
                    <item>
                      <title>Movie.2026.1080p.BluRay</title>
                      <link>https://pt.example.com/download.php?id=3</link>
                      <size>10737418240</size>
                      <newznab:attr name="seeders" value="7"/>
                      <newznab:attr name="downloadvolumefactor" value="0.5"/>
                    </item>
                  </channel>
                </rss>
                """;

        List<TorrentInfo> list = TorznabParser.parse(xml);

        assertEquals(7, list.get(0).getSeeders());
        assertEquals(0.5, list.get(0).getDownloadVolumeFactor(), 0.0001);
    }

    @Test
    void parse_体积仅在enclosure中_从enclosure取值() {
        String xml = wrap("""
                    <item>
                      <title>Movie.2026.1080p</title>
                      <link>https://pt.example.com/download.php?id=4</link>
                      <enclosure url="https://pt.example.com/torrent/4.torrent" length="3221225472" type="application/x-bittorrent"/>
                    </item>
                """);

        assertEquals(3221225472L, TorznabParser.parse(xml).get(0).getSize());
    }

    @Test
    void parse_体积仅在torznab属性中_从属性取值() {
        String xml = wrap("""
                    <item>
                      <title>Movie.2026.1080p</title>
                      <link>https://pt.example.com/download.php?id=9</link>
                      <torznab:attr name="size" value="1234567890"/>
                    </item>
                """);

        assertEquals(1234567890L, TorznabParser.parse(xml).get(0).getSize());
    }

    @Test
    void parse_无link仅有enclosure_下载地址取enclosure的url() {
        String xml = wrap("""
                    <item>
                      <title>Movie.2026.1080p</title>
                      <enclosure url="https://pt.example.com/torrent/5.torrent" length="100" type="application/x-bittorrent"/>
                    </item>
                """);

        assertEquals("https://pt.example.com/torrent/5.torrent", TorznabParser.parse(xml).get(0).getDownloadUrl());
    }

    @Test
    void parse_属性全部缺失_使用安全默认值() {
        String xml = wrap("""
                    <item>
                      <title>Movie.2026.1080p</title>
                      <link>https://pt.example.com/download.php?id=6</link>
                    </item>
                """);

        TorrentInfo t = TorznabParser.parse(xml).get(0);

        assertEquals(0, t.getSeeders());
        assertEquals(0L, t.getSize());
        assertNull(t.getInfoHash());
        // 未提供促销信息时必须按非免费处理，避免误判
        assertEquals(1.0, t.getDownloadVolumeFactor(), 0.0001);
    }

    @Test
    void parse_属性值非数字_回退到默认值而非抛异常() {
        String xml = wrap("""
                    <item>
                      <title>Movie.2026.1080p</title>
                      <link>https://pt.example.com/download.php?id=7</link>
                      <size>not-a-number</size>
                      <torznab:attr name="seeders" value="N/A"/>
                    </item>
                """);

        TorrentInfo t = TorznabParser.parse(xml).get(0);

        assertEquals(0L, t.getSize());
        assertEquals(0, t.getSeeders());
    }

    @Test
    void parse_缺少标题的条目_被丢弃() {
        String xml = wrap("""
                    <item>
                      <link>https://pt.example.com/download.php?id=8</link>
                    </item>
                """);

        assertTrue(TorznabParser.parse(xml).isEmpty());
    }

    @Test
    void parse_既无link也无enclosure的条目_被丢弃() {
        String xml = wrap("""
                    <item>
                      <title>Movie.2026.1080p</title>
                    </item>
                """);

        assertTrue(TorznabParser.parse(xml).isEmpty());
    }

    @Test
    void parse_空结果集_返回空列表() {
        assertTrue(TorznabParser.parse(wrap("")).isEmpty());
    }

    @Test
    void parse_空字符串_返回空列表() {
        assertTrue(TorznabParser.parse("").isEmpty());
        assertTrue(TorznabParser.parse(null).isEmpty());
    }

    @Test
    void parse_非法XML_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> TorznabParser.parse("<rss><channel><item>"));
    }

    @Test
    void parse_含DTD声明_抛异常而非解析_防XXE() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss><channel><item><title>&xxe;</title></item></channel></rss>
                """;

        assertThrows(IllegalArgumentException.class, () -> TorznabParser.parse(xml));
    }

    @Test
    void parse_多条目_全部返回且顺序保持() {
        String xml = wrap("""
                    <item><title>A</title><link>http://x/1</link></item>
                    <item><title>B</title><link>http://x/2</link></item>
                    <item><title>C</title><link>http://x/3</link></item>
                """);

        List<TorrentInfo> list = TorznabParser.parse(xml);

        assertEquals(3, list.size());
        assertEquals("A", list.get(0).getTitle());
        assertEquals("C", list.get(2).getTitle());
    }
}
