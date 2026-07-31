package com.osr.openliststrm.pt.downloader.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloaderTorrentTest {

    private DownloaderTorrent torrent(double progress) {
        DownloaderTorrent t = new DownloaderTorrent();
        t.setHash("abc");
        t.setName("Some.Show.S01E05");
        t.setProgress(progress);
        return t;
    }

    @Test
    void isCompleted_进度为1_判定完成() {
        assertTrue(torrent(1.0).isCompleted());
    }

    @Test
    void isCompleted_进度为0999_判定未完成() {
        assertFalse(torrent(0.999).isCompleted());
    }

    @Test
    void isCompleted_进度为0_判定未完成() {
        assertFalse(torrent(0.0).isCompleted());
    }
}
