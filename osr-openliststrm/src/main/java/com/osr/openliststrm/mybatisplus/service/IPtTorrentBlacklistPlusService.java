package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.pt.task.dto.BatchBlacklistResult;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * PT 种子/发布组手动黑名单 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
public interface IPtTorrentBlacklistPlusService extends IService<PtTorrentBlacklistPlus> {

    /**
     * 拉黑指定下载记录对应的种子（GUID 维度），幂等。
     *
     * @return true=新增成功，false=已存在（幂等命中）
     * @throws IllegalArgumentException 下载记录不存在
     */
    boolean blockRecordGuid(Integer recordId, String reason);

    /**
     * 拉黑指定下载记录标题解析出的发布组，幂等。
     *
     * @return true=新增成功，false=已存在（幂等命中）
     * @throws IllegalArgumentException 下载记录不存在，或标题解析不出发布组
     */
    boolean blockRecordReleaseGroup(Integer recordId, String reason);

    /**
     * 批量拉黑选中下载记录对应的种子（GUID 维度）。
     * <p>
     * 逐条复用 {@link #blockRecordGuid}，单条不满足条件不影响其余条目。
     * </p>
     */
    BatchBlacklistResult blockRecordGuidBatch(List<Integer> recordIds, String reason);

    /**
     * 批量拉黑选中下载记录标题解析出的发布组。
     * <p>
     * 逐条复用 {@link #blockRecordReleaseGroup}；选中的记录常常来自同一个发布组，
     * 那种情况下只有第一条真正落库，其余命中幂等。
     * </p>
     */
    BatchBlacklistResult blockRecordReleaseGroupBatch(List<Integer> recordIds, String reason);

    /**
     * 从种子标题本地解析出发布组（原样大小写，已去首尾空白）；标题为空或解析不出时返回 {@code null}。
     * 与 {@link #blockRecordReleaseGroup} 用的是同一套解析，下载记录页据此判断「这个组拉黑过没有」。
     */
    String releaseGroupOf(String title);

    /** 发布组落库前的归一化（去空白 + 大写），{@code value} 列存的就是它 */
    String normalizeReleaseGroup(String group);

    /**
     * 一次查出 {@code values} 里哪些已在指定类型的黑名单中。
     *
     * @param type   {@link PtTorrentBlacklistPlus#TYPE_GUID} 或 {@link PtTorrentBlacklistPlus#TYPE_RELEASE_GROUP}
     * @param values GUID 类型传 guid_hash，发布组类型传 {@link #normalizeReleaseGroup} 之后的值
     */
    Set<String> findBlockedValues(String type, Collection<String> values);
}
