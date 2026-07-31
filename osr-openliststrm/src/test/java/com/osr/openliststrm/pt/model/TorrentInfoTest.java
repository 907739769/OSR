package com.osr.openliststrm.pt.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentInfoTest {

    @Test
    void isFree_下载量系数为0_判定为免费() {
        TorrentInfo info = new TorrentInfo();
        info.setDownloadVolumeFactor(0.0);
        assertTrue(info.isFree());
    }

    @Test
    void isFree_下载量系数为1_判定为非免费() {
        TorrentInfo info = new TorrentInfo();
        info.setDownloadVolumeFactor(1.0);
        assertFalse(info.isFree());
    }

    @Test
    void isFree_下载量系数为半价_判定为非免费() {
        TorrentInfo info = new TorrentInfo();
        info.setDownloadVolumeFactor(0.5);
        assertFalse(info.isFree());
    }

    @Test
    void 默认下载量系数为1_即非免费() {
        assertFalse(new TorrentInfo().isFree());
    }
}
