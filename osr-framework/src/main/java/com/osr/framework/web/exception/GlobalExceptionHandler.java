package com.osr.framework.web.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import com.osr.common.core.domain.AjaxResult;
import com.osr.common.core.text.Convert;
import com.osr.common.exception.ServiceException;
import com.osr.common.utils.ServletUtils;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.html.EscapeUtil;
import com.osr.common.utils.security.PermissionUtils;

/**
 * 全局异常处理器
 * 
 * @author osr
 */
@RestControllerAdvice(basePackages = {"com.osr.web.controller.system", "com.osr.web.controller.monitor", "com.osr.web.controller.common", "com.osr.quartz.controller", "com.osr.generator.controller", "com.osr.openliststrm.controller"})
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 请求方式不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return AjaxResult.error(e.getMessage());
    }

    /**
     * 拦截未知的运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public AjaxResult handleRuntimeException(RuntimeException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        if (isClientAbort(e))
        {
            log.debug("请求地址'{}',客户端提前断开连接", requestURI);
            return AjaxResult.error(e.getMessage());
        }
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        return AjaxResult.error(e.getMessage());
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        if (isClientAbort(e))
        {
            log.debug("请求地址'{}',客户端提前断开连接", requestURI);
            return AjaxResult.error(e.getMessage());
        }
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(e.getMessage());
    }

    /**
     * 客户端提前断开连接（用户切走页面、刷新、关标签）导致的写响应失败。
     *
     * <p>降级成 DEBUG 而不是 ERROR：这不是服务端故障，也无从处理——响应已经写不出去了，
     * 记下来既不能修也不用修。而 sys-error.log 的<b>全部价值就在于噪音为零、一眼扫得完</b>
     * （后端异常不进 docker stdout，它是排查线上问题的唯一入口）。实测一份 385 行的
     * sys-error.log 里只有 3 条 ERROR，其中 2 条是这个——每条还拖着一整份堆栈。
     *
     * <p>按类名后缀 + message 关键字判断，不直接 import Tomcat 的 ClientAbortException：
     * 那会让 osr-framework 绑死在特定 servlet 容器上，而这里要认的其实是「对端没了」这件事，
     * 它在不同容器/不同层（Spring 的 HttpMessageNotWritableException 包着 IOException）
     * 表现成不同的类型。链上任一环命中即可。
     */
    private boolean isClientAbort(Throwable e)
    {
        for (Throwable t = e; t != null; t = t.getCause())
        {
            if (t.getClass().getName().endsWith("ClientAbortException"))
            {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null)
            {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe") || lower.contains("connection reset by peer"))
                {
                    return true;
                }
            }
            // 自引用的 cause 会让这个循环停不下来（罕见但确实存在于某些包装异常里）
            if (t.getCause() == t)
            {
                break;
            }
        }
        return false;
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public Object handleServiceException(ServiceException e, HttpServletRequest request)
    {
        log.error(e.getMessage(), e);
        if (ServletUtils.isAjaxRequest(request))
        {
            return AjaxResult.error(e.getMessage());
        }
        else
        {
            return new ModelAndView("error/service", "errorMessage", e.getMessage());
        }
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public AjaxResult handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求路径中缺少必需的路径变量'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求路径中缺少必需的路径变量[%s]", e.getVariableName()));
    }

    /**
     * 请求参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public AjaxResult handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e,
            HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        String value = Convert.toStr(e.getValue());
        if (StringUtils.isNotEmpty(value))
        {
            value = EscapeUtil.clean(value);
        }
        log.error("请求参数类型不匹配'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求参数类型不匹配，参数[%s]要求类型为：'%s'，但输入值为：'%s'", e.getName(), e.getRequiredType().getName(), value));
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e)
    {
        log.error(e.getMessage(), e);
        String message = e.getAllErrors().get(0).getDefaultMessage();
        return AjaxResult.error(message);
    }

}
