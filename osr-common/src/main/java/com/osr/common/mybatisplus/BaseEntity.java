package com.osr.common.mybatisplus;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * @Author Jack
 * @Date 2025/7/10 16:08
 * @Version 1.0.0
 */
@Data
public class BaseEntity implements Serializable {

    @TableField(fill = FieldFill.INSERT)
    private String createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateTime;

    @TableField(exist = false)
    private Map<String, Object> params;

}
