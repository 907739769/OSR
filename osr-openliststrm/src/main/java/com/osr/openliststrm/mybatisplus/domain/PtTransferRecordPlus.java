package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * <p>
 * PT 转移做种记录：一个种子从源下载器搬到目标下载器的全过程。
 * </p>
 * <p>
 * 转移是<b>跨轮次</b>的状态机：本轮把种子加到目标端并触发校验，校验要跑几分钟到几十分钟，
 * 只能等下一轮再来看结果。因此中间态必须落库——放内存里的话进程一重启，目标端就留下一批
 * 暂停态的孤儿种子，既不做种也没人再管它们。
 * </p>
 *
 * @author Jack
 * @since 2026-08-15
 */
@Getter
@Setter
@TableName("pt_transfer_record")
public class PtTransferRecordPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 所属规则 ID */
    @TableField("rule_id")
    private Integer ruleId;

    /** 种子 infohash，小写 */
    @TableField("torrent_hash")
    private String torrentHash;

    /** 种子名，仅用于展示与排查 */
    @TableField("torrent_name")
    private String torrentName;

    /** 种子体积（字节） */
    @TableField("size_bytes")
    private Long sizeBytes;

    /** 源下载器 ID */
    @TableField("source_downloader_id")
    private Integer sourceDownloaderId;

    /** 目标下载器 ID */
    @TableField("target_downloader_id")
    private Integer targetDownloaderId;

    /** 源下载器上的保存路径 */
    @TableField("source_save_path")
    private String sourceSavePath;

    /** 目标下载器上的保存路径（已应用路径映射） */
    @TableField("target_save_path")
    private String targetSavePath;

    /** 状态，取值见 {@code com.osr.openliststrm.pt.transfer.TransferState} */
    @TableField("state")
    private String state;

    /** 失败原因，失败时必填 */
    @TableField("fail_reason")
    private String failReason;

    /** 源下载器上的种子是否已删除 0-否 1-是（从不删除文件） */
    @TableField("source_deleted")
    private String sourceDeleted;

    /** 目标端开始校验的时间，用于判定校验超时 */
    @TableField("verify_start_time")
    private Date verifyStartTime;

    /** 转移终结（完成或失败）的时间 */
    @TableField("finish_time")
    private Date finishTime;

    /**
     * 源种子是否已被删除。
     * <p>
     * 命名不能是 {@code isSourceDeleted()}——与 Lombok 生成的 {@code getSourceDeleted()}
     * 撞成"同一属性两个类型不一致的 getter"，MyBatis 会在第一条 INSERT 时抛歧义异常。
     * 理由与 {@code PtTransferRulePlus#enabledOn()} 处的说明相同。
     * </p>
     */
    public boolean sourceDeletedOn() {
        return "1".equals(sourceDeleted);
    }
}
