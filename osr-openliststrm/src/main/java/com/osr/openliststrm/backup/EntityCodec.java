package com.osr.openliststrm.backup;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按 {@code @TableField} / {@code @TableId} 注解读写一个 MP 实体的「库表列」。
 * <p>
 * 只认实体类<b>自己声明</b>的、真正落库的字段：{@code BaseEntity} 的 createTime/params 之类、
 * {@code exist = false} 的列表页辅助字段（订阅上的 sortBy、inLibraryCount……）一概不进备份。
 * 不用 FastJSON 直接序列化整个实体，是因为那会把这些辅助字段一起带出去，
 * 恢复时再原样写回就可能撞上别的语义。
 *
 * @author Jack
 */
final class EntityCodec<T> {

    private final Class<T> type;
    private final Field idField;
    private final String idColumn;
    /** 属性名 → 字段，按声明顺序，备份文件里的键顺序与实体一致，读起来顺眼 */
    private final Map<String, Field> columns = new LinkedHashMap<>();
    private final Map<String, String> columnNames = new LinkedHashMap<>();

    EntityCodec(Class<T> type) {
        this.type = type;
        Field id = null;
        String idCol = null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            TableId tableId = field.getAnnotation(TableId.class);
            if (tableId != null) {
                field.setAccessible(true);
                id = field;
                idCol = StringUtils.isNotBlank(tableId.value()) ? tableId.value() : StringUtils.camelToUnderline(field.getName());
                continue;
            }
            TableField tableField = field.getAnnotation(TableField.class);
            if (tableField != null && !tableField.exist()) {
                continue;
            }
            field.setAccessible(true);
            columns.put(field.getName(), field);
            columnNames.put(field.getName(), tableField != null && StringUtils.isNotBlank(tableField.value())
                    ? tableField.value() : StringUtils.camelToUnderline(field.getName()));
        }
        if (id == null) {
            throw new IllegalStateException(type.getSimpleName() + " 没有 @TableId 字段");
        }
        this.idField = id;
        this.idColumn = idCol;
    }

    Class<T> type() {
        return type;
    }

    Set<String> properties() {
        return Collections.unmodifiableSet(columns.keySet());
    }

    String column(String property) {
        return columnNames.get(property);
    }

    String idColumn() {
        return idColumn;
    }

    Object id(T entity) {
        return read(idField, entity);
    }

    /** 全部库表列（不含主键）转成 JSON，值为 null 的列也保留，恢复时靠它区分「清空」与「没提」 */
    JSONObject toJson(T entity) {
        JSONObject json = new JSONObject();
        columns.forEach((name, field) -> json.put(name, read(field, entity)));
        return json;
    }

    /** JSON 转实体，只认本实体的库表列，类型由 FastJSON 按字段类型转换 */
    T fromJson(JSONObject json) {
        JSONObject known = new JSONObject();
        json.forEach((k, v) -> {
            if (columns.containsKey(k)) {
                known.put(k, v);
            }
        });
        return known.toJavaObject(type);
    }

    Object get(T entity, String property) {
        Field field = columns.get(property);
        return field == null ? null : read(field, entity);
    }

    void set(T entity, String property, Object value) {
        Field field = columns.get(property);
        if (field == null) {
            return;
        }
        try {
            field.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("写字段失败：" + type.getSimpleName() + "." + property, e);
        }
    }

    /**
     * 两个值在「库里是不是同一个值」意义上相等。BigDecimal 要去掉尾零再比：
     * 备份里的 {@code 1.5} 与库里读出来的 {@code 1.50} 是同一个体积上限。
     */
    static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private Object read(Field field, T entity) {
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读字段失败：" + type.getSimpleName() + "." + field.getName(), e);
        }
    }
}
