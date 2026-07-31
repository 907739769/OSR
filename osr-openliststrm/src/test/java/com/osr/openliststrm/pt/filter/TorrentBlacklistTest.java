package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentBlacklistTest {

    private PtTorrentBlacklistPlus rule(String type, String value) {
        PtTorrentBlacklistPlus r = new PtTorrentBlacklistPlus();
        r.setType(type);
        r.setValue(value);
        return r;
    }

    @Test
    void from_发布组归一化为大写() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(List.of(
                rule(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP, "chdweb")));

        assertTrue(blacklist.releaseGroupsUpper().contains("CHDWEB"));
    }

    @Test
    void from_null列表_返回EMPTY等价的空集合() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(null);

        assertTrue(blacklist.guidHashes().isEmpty());
        assertTrue(blacklist.releaseGroupsUpper().isEmpty());
    }

    @Test
    void from_空列表_返回EMPTY等价的空集合() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(List.of());

        assertTrue(blacklist.guidHashes().isEmpty());
        assertTrue(blacklist.releaseGroupsUpper().isEmpty());
    }

    @Test
    void from_重复value去重() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(List.of(
                rule(PtTorrentBlacklistPlus.TYPE_GUID, "abc123"),
                rule(PtTorrentBlacklistPlus.TYPE_GUID, "abc123")));

        assertEquals(1, blacklist.guidHashes().size());
    }

    @Test
    void from_GUID与发布组分别归类() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(List.of(
                rule(PtTorrentBlacklistPlus.TYPE_GUID, "abc123"),
                rule(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP, "MTeam")));

        assertEquals(Set.of("abc123"), blacklist.guidHashes());
        assertEquals(Set.of("MTEAM"), blacklist.releaseGroupsUpper());
    }

    @Test
    void EMPTY_两个集合均为空() {
        assertTrue(TorrentBlacklist.EMPTY.guidHashes().isEmpty());
        assertTrue(TorrentBlacklist.EMPTY.releaseGroupsUpper().isEmpty());
    }
}
