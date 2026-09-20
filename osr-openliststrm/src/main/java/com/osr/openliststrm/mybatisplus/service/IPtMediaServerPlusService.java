package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;

import java.util.List;

/**
 * <p>
 * PT 媒体服务器配置 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
public interface IPtMediaServerPlusService extends IService<PtMediaServerPlus> {

    /**
     * 取<b>全部</b>启用中的媒体服务器，按 id 升序；一台都没启用时返回空表。
     * <p>
     * 这里刻意返回列表而不是「当前生效的那一台」。早先的 {@code getActive()} 是
     * {@code enabled='1' ORDER BY id LIMIT 1}，而配置页是个能添任意多台、每台一个独立
     * 启用开关的列表——用户配上 Emby + Jellyfin 两台、两台都显示「启用」、两台都能测试连接成功，
     * <b>实际只有 id 最小的那台参与入库判定</b>，另一台是彻底的死配置，页面上没有任何一处看得出来。
     * 症状是「我明明配了 Jellyfin，订阅进度却按 Emby 走」，而日志里一切正常。
     * </p>
     * <p>
     * 现在语义定成「都启用 = 都查，任一命中即算入库」，与页面呈现一致，也顺带支持了
     * 「电影在 Emby、剧集在 Jellyfin」「新旧两台并存迁移中」这两种真实用法。
     * 消费方见 {@code SubscriptionService#queryLibrary}。
     * </p>
     */
    List<PtMediaServerPlus> listActive();

    /**
     * 写回一次业务访问的连通结果。
     * <p>
     * <b>必须走 LambdaUpdateWrapper 显式 set，绝不能 {@code updateById(实体)}</b>，两条理由各自都够：
     * </p>
     * <ol>
     *   <li>连通成功时要把 {@code last_check_error} <b>清空</b>，而 MyBatis-Plus 默认的
     *       {@code FieldStrategy.NOT_NULL} 会跳过 null 字段，清空根本写不进去——故障恢复之后
     *       页面上永远挂着上一次的错误原因。</li>
     *   <li>{@code api_key} 带 {@link com.osr.openliststrm.mybatisplus.handler.EncryptedStringTypeHandler}，
     *       而调用方手里那份实例可能是经 {@code maskSensitiveFields} 脱敏过的。整实体写回等于拿
     *       一份不完整的快照覆盖整行，与 {@code updateAutoSearchMissState} 那条坑同源。</li>
     * </ol>
     *
     * @param error 失败原因；连通成功时传 null，该列会被清空
     */
    void updateProbeResult(Integer id, boolean ok, String error);
}
