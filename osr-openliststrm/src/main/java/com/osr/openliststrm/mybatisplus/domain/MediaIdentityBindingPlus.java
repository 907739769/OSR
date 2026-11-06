package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 文件与作品的持久绑定。自动识别结果不写入此表。 */
@Getter
@Setter
@TableName("media_identity_binding")
public class MediaIdentityBindingPlus {
    @TableId(type = IdType.INPUT)
    private String bindingKey;
    private String sourceKind;
    private Integer downloadId;
    private String downloadVersion;
    private String sourcePath;
    private String relativePath;
    private String fileNameHash;
    private Long fileSize;
    private String tmdbId;
    private String mediaType;
    private String season;
    private String episode;
}
