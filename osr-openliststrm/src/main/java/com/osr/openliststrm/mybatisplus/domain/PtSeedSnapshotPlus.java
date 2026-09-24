package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.Date;

/**
 * 保种看板的每日快照：每台下载器每天一行，当天内每小时覆盖一次（最后一次即当日收盘值），语义见 20260807-pt-seed-snapshot.sql。
 * <p>
 * 刻意不继承 {@code BaseEntity}：这是周期任务写的统计表，不需要 create_by 之类的审计列。
 *
 * @author Jack
 */
@Getter
@Setter
@TableName("pt_seed_snapshot")
public class PtSeedSnapshotPlus {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("downloader_id")
    private Integer downloaderId;

    @TableField("snapshot_date")
    private LocalDate snapshotDate;

    /** 下载器里的种子总数 */
    @TableField("torrent_count")
    private Integer torrentCount;

    /** 已下完（保种中）的种子数 */
    @TableField("seeding_count")
    private Integer seedingCount;

    /** 已下完种子的体积之和（字节） */
    @TableField("seeding_size")
    private Long seedingSize;

    /** 现存种子的上传量之和（字节）；删种会让它变小，只作累计计数器取不到时的兜底 */
    @TableField("uploaded_sum")
    private Long uploadedSum;

    /** 下载器自己记的累计上传（字节），单调递增，按天求差即当日上传量；取不到为 null */
    @TableField("cumulative_uploaded")
    private Long cumulativeUploaded;

    @TableField("update_time")
    private Date updateTime;
}
