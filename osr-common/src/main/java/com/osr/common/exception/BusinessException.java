package com.osr.common.exception;

/**
 * 业务异常类 (for REST API)
 */
public class BusinessException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int code;
    private final String message;

    public BusinessException(int code, String message)
    {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(String message)
    {
        super(message);
        this.code = 500;
        this.message = message;
    }

    public int getCode() { return code; }
    @Override
    public String getMessage() { return message; }
}
