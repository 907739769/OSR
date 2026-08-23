package com.osr.common.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「同一个对象只说一次」——按键去重的日志闸门，有界 LRU。
 *
 * <p>这是 {@link FaultThrottle}、{@link RoundHeartbeat} 之外的第三种日志节流形态，
 * 三者治的毛病不同：{@code FaultThrottle} 管「出错了别刷屏」，{@code RoundHeartbeat}
 * 管「没出错也得让人知道你还活着」，本类管的是<b>正常路径上被反复处理的同一批对象</b>。
 *
 * <p>原形是 PT 的 RSS 轮询：拉取窗口是 24 小时，每 5 分半拉一轮，于是同一个种子会在
 * 窗口里被反复拉到、反复走一遍匹配。「种子未匹配到任何订阅」这条 DEBUG 因此按
 * <b>种子数 × 轮数</b> 增长——实测 886 个不同种子在 10.5 小时里写出 39913 行，
 * 占掉整份日志的 <b>93%</b>（比曾经被清理掉的 MyBatis SQL 那次还狠）。
 * 而它<b>不能简单删掉</b>：「我订了这部剧、站上明明有资源、为什么没推给我」是真实的
 * 排查场景，且这条是唯一的记录（没匹配上就没有订阅可挂，{@code pt_search_log} 里也没有）。
 * 去重之后 886 行照旧写全，排查价值一分不少，量掉 97.8%。
 *
 * <p>用法：
 * <pre>{@code
 * if (unmatchedSeen.firstTime(torrent.getTitle())) {
 *     log.debug("种子未匹配到任何订阅：{}", torrent.getTitle());
 * }
 * }</pre>
 *
 * <p><b>键应当取「打印出来的那个东西」</b>，而不是对象的技术主键。上例里同一个标题在两个
 * 站点各有一条种子（guid 不同）时，按 guid 去重会写出两行<b>逐字相同</b>的日志——
 * 那行文本里并没有站点信息，读的人分不出它们，等于没去重。
 *
 * <p><b>容量满了退化成「多打几行」，不会出错。</b>用访问序 LRU 而不是插入序：一个每轮都
 * 出现的种子会一直被 {@link #firstTime} 摸到而保持在热端，永远不会被新种子挤掉再重打一遍；
 * 换成插入序的话稳态下反而是最活跃的那批被反复驱逐，正好把去重的效果抵消掉。
 *
 * <p><b>被单例 bean 当实例字段持有是正确的</b>，理由与 {@link FaultThrottle} 完全相同：
 * 存的是需要跨轮次存活的组件级状态，放进方法局部变量的话每轮都从空开始，去重就不存在了。
 * 内部加锁，多线程并发调用安全。
 *
 * @author Jack
 */
public class LogOnce {

    /**
     * 默认容量。按 PT 的场景估：一个 24 小时 RSS 窗口里不重复的种子约 900 条，
     * 4096 留了四倍余量；键是种子标题（百来字节），满载也就几百 KB。
     */
    public static final int DEFAULT_CAPACITY = 4096;

    private final Map<String, Boolean> seen;

    public LogOnce() {
        this(DEFAULT_CAPACITY);
    }

    public LogOnce(int capacity) {
        int max = Math.max(1, capacity);
        // accessOrder=true：见 javadoc 里「访问序而不是插入序」那段
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > max;
            }
        };
    }

    /**
     * 这个键是不是第一次见到？
     *
     * <p>返回 false 时也会把它挪到 LRU 热端——「一直在出现」的键不该因为老化被驱逐、
     * 然后被当成新的再打一遍。
     *
     * @param key 建议直接用要打印的那段文本，理由见类注释
     * @return 首次见到返回 true（调用方应当输出），此后恒返回 false
     */
    public boolean firstTime(String key) {
        if (key == null) {
            // 拿不到键就无从去重。这里放行而不是吞掉：宁可多打一行，也不要让一条
            // 本该出现的日志因为某个字段恰好为空而静默消失
            return true;
        }
        synchronized (seen) {
            return seen.put(key, Boolean.TRUE) == null;
        }
    }

    /** 忘掉一个键，使它下次再被 {@link #firstTime} 判成首次 */
    public void forget(String key) {
        if (key == null) {
            return;
        }
        synchronized (seen) {
            seen.remove(key);
        }
    }

    /** 全部忘掉 */
    public void clear() {
        synchronized (seen) {
            seen.clear();
        }
    }

    /** 当前记住了多少个键，主要供测试与排查用 */
    public int size() {
        synchronized (seen) {
            return seen.size();
        }
    }
}
