package com.osr.common.core.domain;

import java.io.Serializable;

/**
 * 统一 API 响应包装
 *
 * @param <T> 数据类型
 */
public class Result<T> implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 状态码 */
    private int code;

    /** 消息 */
    private String message;

    /** 数据 */
    private T data;

    public Result() {}

    public Result(int code, String message, T data)
    {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success()
    {
        return new Result<>(200, "操作成功", null);
    }

    public static <T> Result<T> success(T data)
    {
        return new Result<>(200, "操作成功", data);
    }

    public static <T> Result<T> error()
    {
        return new Result<>(500, "操作失败", null);
    }

    public static <T> Result<T> error(String message)
    {
        return new Result<>(500, message, null);
    }

    public static <T> Result<T> error(int code, String message)
    {
        return new Result<>(code, message, null);
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
