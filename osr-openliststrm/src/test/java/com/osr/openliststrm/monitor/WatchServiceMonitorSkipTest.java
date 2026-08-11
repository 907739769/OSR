package com.osr.openliststrm.monitor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监控遍历的目录跳过边界。真正危险的不是"漏跳一个临时目录"（下游还有第二道防线），
 * 而是"把起始目录本身跳掉"——那会让监控起来了却一个文件都不处理，且完全静默。
 *
 * @author Jack
 */
class WatchServiceMonitorSkipTest {

    /** 与 OpenListHelper 默认规则同形：Transmission 的 mkdtemp 模板 <种子名>__XXXXXX */
    private static boolean transientName(String name) {
        return name.matches(".+__[0-9A-Za-z]{6}");
    }

    private static WatchServiceMonitor monitor(Path root) {
        return new WatchServiceMonitor(root, WatchServiceMonitorSkipTest::transientName);
    }

    @Test
    void 子目录命中规则_整棵跳过() {
        Path root = Paths.get("/download/osr/剧集");
        WatchServiceMonitor m = monitor(root);

        assertTrue(m.skipSubtree(root.resolve("Show.S01-ADWeb__kefDJG"), root));
    }

    @Test
    void 起始目录自己永远不跳_哪怕名字命中规则() {
        // 用户把监控目录就命名成这个形状时，跳过它等于静默关掉整个监控
        Path root = Paths.get("/download/osr__kefDJG");
        WatchServiceMonitor m = monitor(root);

        assertFalse(m.skipSubtree(root, root));
    }

    @Test
    void 普通子目录不跳() {
        Path root = Paths.get("/download/osr/剧集");
        WatchServiceMonitor m = monitor(root);

        assertFalse(m.skipSubtree(root.resolve("2026"), root));
        assertFalse(m.skipSubtree(root.resolve("Show.S01-ADWeb"), root));
    }

    @Test
    void 未传过滤器时退化为不跳任何目录() {
        Path root = Paths.get("/download/osr/剧集");
        WatchServiceMonitor m = new WatchServiceMonitor(root);

        assertFalse(m.skipSubtree(root.resolve("Show.S01-ADWeb__kefDJG"), root));
    }

    @Test
    void 过滤器传null时不抛异常也不跳() {
        Path root = Paths.get("/download/osr/剧集");
        WatchServiceMonitor m = new WatchServiceMonitor(root, null);

        assertFalse(m.skipSubtree(root.resolve("Show.S01-ADWeb__kefDJG"), root));
    }
}
