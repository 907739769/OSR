package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("osr_background_job")
public class BackgroundJobPlus extends BaseEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long ownerId;
    private String kind;
    private String state;
    private String itemsJson;
    private Boolean cancelRequested;
}
