package com.osr.openliststrm.pt.indexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorznabCapsParserTest {

    @Test
    void parse_movie与tv均支持imdbid和tmdbid() {
        String xml = """
                <caps>
                  <searching>
                    <search available="yes" supportedParams="q"/>
                    <tv-search available="yes" supportedParams="q,season,ep,imdbid,tmdbid"/>
                    <movie-search available="yes" supportedParams="q,imdbid,tmdbid"/>
                  </searching>
                </caps>
                """;

        IndexerCapability cap = TorznabCapsParser.parse(xml);

        assertTrue(cap.movieImdbSupported());
        assertTrue(cap.movieTmdbSupported());
        assertTrue(cap.tvImdbSupported());
        assertTrue(cap.tvTmdbSupported());
    }

    @Test
    void parse_只支持imdbid不支持tmdbid() {
        String xml = """
                <caps>
                  <searching>
                    <movie-search available="yes" supportedParams="q,imdbid"/>
                  </searching>
                </caps>
                """;

        IndexerCapability cap = TorznabCapsParser.parse(xml);

        assertTrue(cap.movieImdbSupported());
        assertFalse(cap.movieTmdbSupported());
    }

    @Test
    void parse_available为no时视为不支持() {
        String xml = """
                <caps>
                  <searching>
                    <movie-search available="no" supportedParams="q,imdbid,tmdbid"/>
                  </searching>
                </caps>
                """;

        IndexerCapability cap = TorznabCapsParser.parse(xml);

        assertFalse(cap.movieImdbSupported());
        assertFalse(cap.movieTmdbSupported());
    }

    @Test
    void parse_无searching节点_返回NONE() {
        assertEquals(IndexerCapability.NONE, TorznabCapsParser.parse("<caps></caps>"));
    }

    @Test
    void parse_无movieSearch或tvSearch节点_对应能力为false() {
        String xml = """
                <caps>
                  <searching>
                    <search available="yes" supportedParams="q"/>
                  </searching>
                </caps>
                """;

        IndexerCapability cap = TorznabCapsParser.parse(xml);

        assertFalse(cap.movieImdbSupported());
        assertFalse(cap.tvImdbSupported());
    }

    @Test
    void parse_空字符串或null_返回NONE() {
        assertEquals(IndexerCapability.NONE, TorznabCapsParser.parse(""));
        assertEquals(IndexerCapability.NONE, TorznabCapsParser.parse(null));
    }

    @Test
    void parse_非法XML_返回NONE而不抛异常() {
        assertEquals(IndexerCapability.NONE, TorznabCapsParser.parse("<caps><searching>"));
    }

    @Test
    void parseCategories_正常响应_解析出父子分类树() {
        String xml = """
                <caps>
                  <categories>
                    <category id="2000" name="Movies">
                      <subcat id="2010" name="Movies/Foreign"/>
                      <subcat id="2020" name="Movies/Other"/>
                    </category>
                    <category id="5000" name="TV">
                      <subcat id="5040" name="TV/HD"/>
                    </category>
                  </categories>
                </caps>
                """;

        List<CategoryOption> categories = TorznabCapsParser.parseCategories(xml);

        assertEquals(2, categories.size());
        assertEquals(2000, categories.get(0).id());
        assertEquals("Movies", categories.get(0).name());
        assertEquals(2, categories.get(0).children().size());
        assertEquals(2010, categories.get(0).children().get(0).id());
        assertEquals("Movies/Foreign", categories.get(0).children().get(0).name());
        assertEquals(5000, categories.get(1).id());
        assertEquals(1, categories.get(1).children().size());
        assertEquals(5040, categories.get(1).children().get(0).id());
    }

    @Test
    void parseCategories_无subcat的分类_children为空列表() {
        String xml = """
                <caps>
                  <categories>
                    <category id="8000" name="Other"/>
                  </categories>
                </caps>
                """;

        List<CategoryOption> categories = TorznabCapsParser.parseCategories(xml);

        assertEquals(1, categories.size());
        assertTrue(categories.get(0).children().isEmpty());
    }

    @Test
    void parseCategories_无categories节点_返回空列表() {
        assertTrue(TorznabCapsParser.parseCategories("<caps></caps>").isEmpty());
    }

    @Test
    void parseCategories_空字符串或null_返回空列表() {
        assertTrue(TorznabCapsParser.parseCategories("").isEmpty());
        assertTrue(TorznabCapsParser.parseCategories(null).isEmpty());
    }

    @Test
    void parseCategories_非法XML_返回空列表而不抛异常() {
        assertTrue(TorznabCapsParser.parseCategories("<caps><categories>").isEmpty());
    }
}
