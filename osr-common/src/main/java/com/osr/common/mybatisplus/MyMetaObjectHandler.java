package com.osr.common.mybatisplus;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * MyBatis-Plus 自动填充处理器
 * 使用 java.util.Date + String.class 匹配实体类 String 类型字段
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void insertFill(MetaObject metaObject) {
        String now = SDF.format(new Date());
        this.strictInsertFill(metaObject, "createTime", String.class, now);
        this.strictInsertFill(metaObject, "updateTime", String.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", String.class, SDF.format(new Date()));
    }

}
