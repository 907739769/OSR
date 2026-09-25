package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * STRM 增量扫描的叶子目录快照，语义见 20260802-strm-incremental-scan.sql。
 * <p>
 * 刻意不继承 {@code BaseEntity}：这是扫描器内部的缓存表，不需要 create_by 之类的审计列，
 * 也永远不会出现在任何页面上。
 *
 * @author Jack
 */
@Getter
@Setter
@TableName("openlist_strm_dir_snapshot")
public class OpenlistStrmDirSnapshotPlus {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** SHA-256(dirPath)，唯一键 */
    @TableField("path_hash")
    private String pathHash;

    /** 网盘目录路径（不带末尾斜杠） */
    @TableField("dir_path")
    private String dirPath;

    /** 列父目录时拿到的该目录修改时间，原样保存 */
    @TableField("modified")
    private String modified;

    /** 当时生效的 STRM 设置指纹 */
    @TableField("settings_sign")
    private String settingsSign;

    /** 最后一次确认该快照的时间 */
    @TableField("scanned_time")
    private Date scannedTime;
}
