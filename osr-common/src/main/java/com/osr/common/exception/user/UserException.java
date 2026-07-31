package com.osr.common.exception.user;

import com.osr.common.exception.base.BaseException;

/**
 * 用户信息异常类
 * 
 * @author osr
 */
public class UserException extends BaseException
{
    private static final long serialVersionUID = 1L;

    public UserException(String code, Object[] args)
    {
        super("user", code, args, null);
    }
}
