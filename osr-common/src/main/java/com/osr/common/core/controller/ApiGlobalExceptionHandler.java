package com.osr.common.core.controller;

import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.osr.common.core.domain.Result;
import com.osr.common.exception.BusinessException;

/**
 * API 全局异常处理器 (REST API only)
 */
@RestControllerAdvice(basePackages = {"com.osr.openliststrm.controller.api", "com.osr.web.controller.api"})
public class ApiGlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(ApiGlobalExceptionHandler.class);

    /**
     * 业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e)
    {
        log.error("业务异常: {}", e.getMessage(), e);
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 请求方式不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e)
    {
        log.warn("请求方式不支持: {}", e.getMethod());
        return Result.error(405, "不支持的请求方法");
    }

    /**
     * BindException 异常 (包括 @Validated 参数绑定异常)
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e)
    {
        log.warn("参数绑定异常: {}", e.getMessage());
        String message = e.getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse("参数绑定失败");
        return Result.error(400, message);
    }

    /**
     * MethodArgumentNotValidException 异常
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValidException(org.springframework.web.bind.MethodArgumentNotValidException e)
    {
        log.warn("参数验证异常: {}", e.getMessage());
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String message = fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse("参数验证失败");
        return Result.error(400, message);
    }

    /**
     * ConstraintViolationException 异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e)
    {
        log.warn("约束校验异常: {}", e.getMessage());
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("参数校验失败");
        return Result.error(400, message);
    }

    /**
     * MethodArgumentTypeMismatchException 异常
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e)
    {
        log.warn("参数类型不匹配: {}", e.getMessage());
        return Result.error(400, "请求参数类型不匹配");
    }

    /**
     * RuntimeException 异常
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntimeException(RuntimeException e)
    {
        log.error("系统运行时异常: {}", e.getMessage(), e);
        return Result.error(500, "系统内部错误");
    }

    /**
     * Exception 异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e)
    {
        log.error("系统内部异常: {}", e.getMessage(), e);
        return Result.error(500, "系统内部错误");
    }
}
