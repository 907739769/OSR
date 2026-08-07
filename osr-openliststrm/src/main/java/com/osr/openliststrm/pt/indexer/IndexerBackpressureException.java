package com.osr.openliststrm.pt.indexer;

import java.io.IOException;

/**
 * 本地背压导致这次请求<b>根本没有发出去</b>：索引器正处于限流冷却期，或等待串行/全局许可超时。
 * <p>
 * 与所有"发出去了但失败"的异常（连接失败、读超时、非 2xx）是完全不同的性质——它描述的是
 * <b>我方</b>的节流状态，与索引器是否健康、配置是否正确毫无关系。之所以要单独一个类型，
 * 是因为 {@link com.osr.openliststrm.pt.task.RssPollService#pollOne} 的 {@code catch (Exception)}
 * 会把它当作真失败累加 {@code fail_count}，于是出现一条正反馈：
 * </p>
 * <pre>
 *   命中 429 → penalize 冷却 300 秒（此次刻意不计失败）
 *   → 冷却期内的后续轮次全部快速失败 → 每轮 fail_count +1
 *   → 退避把轮询间隔放大 2/4/8…32 倍 → 两次成功拉取之间的窗口从几分钟变成几小时
 *   → RSS 覆盖度校验报"拉取窗口覆盖不全"
 * </pre>
 * <p>
 * 也就是说 {@link IndexerHttpException#isThrottled()} 那条"限流不计失败"的设计，会被紧随其后
 * 的几轮快速失败原样绕开，用户看到的现象是"缩短轮询间隔反而漏拉更多"（请求更密 → 更容易撞
 * 冷却 → 退避更凶）。请求都没发出去就不该记在索引器账上，这是本类存在的全部理由。
 * </p>
 *
 * @author Jack
 */
public class IndexerBackpressureException extends IOException {

    private static final long serialVersionUID = 1L;

    public IndexerBackpressureException(String message) {
        super(message);
    }
}
