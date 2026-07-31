package com.osr.openliststrm.mybatisplus.handler;

import com.osr.common.utils.CredentialCipher;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 敏感字段（PT 下载器密码、索引器 apikey）透明加解密 TypeHandler：写库前加密，读出后解密，
 * 业务代码全程只接触明文，无需在每个调用点手动加解密。绑定方式见 {@code @TableField(typeHandler = ...)}，
 * 实体类需加 {@code @TableName(autoResultMap = true)} 才能让 MyBatis-Plus 自动生成的查询走 ResultMap 解密。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class EncryptedStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, CredentialCipher.encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return CredentialCipher.decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return CredentialCipher.decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return CredentialCipher.decrypt(cs.getString(columnIndex));
    }
}
