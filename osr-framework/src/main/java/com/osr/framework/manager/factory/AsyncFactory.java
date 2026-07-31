package com.osr.framework.manager.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.osr.common.constant.Constants;
import com.osr.common.utils.AddressUtils;
import com.osr.common.utils.IpUtils;
import com.osr.common.utils.LogUtils;
import com.osr.common.utils.ServletUtils;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.http.UserAgentUtils;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.system.domain.SysLogininfor;
import com.osr.system.domain.SysOperLog;
import com.osr.system.service.ISysOperLogService;
import com.osr.system.service.impl.SysLogininforServiceImpl;

/**
 * 异步工厂（产生任务用）
 * 
 * @author liuhulu
 *
 */
public class AsyncFactory
{
    private static final Logger sys_user_logger = LoggerFactory.getLogger("sys-user");

    public static Runnable recordOper(final SysOperLog operLog)
    {
        return () -> {
            operLog.setOperLocation(AddressUtils.getRealAddressByIP(operLog.getOperIp()));
            SpringUtils.getBean(ISysOperLogService.class).insertOperlog(operLog);
        };
    }

    public static Runnable recordLogininfor(final String username, final String status, final String message, final Object... args)
    {
        final String userAgent = ServletUtils.getRequest().getHeader("User-Agent");
        final String ip = ServletUtils.getRequest() != null ? IpUtils.getIpAddr(ServletUtils.getRequest()) : "0.0.0.0";
        return () -> {
            String address = AddressUtils.getRealAddressByIP(ip);
            StringBuilder s = new StringBuilder();
            s.append(LogUtils.getBlock(ip));
            s.append(address);
            s.append(LogUtils.getBlock(username));
            s.append(LogUtils.getBlock(status));
            s.append(LogUtils.getBlock(message));
            sys_user_logger.info(s.toString(), args);
            String os = UserAgentUtils.getOperatingSystem(userAgent);
            String browser = UserAgentUtils.getBrowser(userAgent);
            SysLogininfor logininfor = new SysLogininfor();
            logininfor.setLoginName(username);
            logininfor.setIpaddr(ip);
            logininfor.setLoginLocation(address);
            logininfor.setBrowser(browser);
            logininfor.setOs(os);
            logininfor.setMsg(message);
            if (StringUtils.equalsAny(status, Constants.LOGIN_SUCCESS, Constants.LOGOUT, Constants.REGISTER))
            {
                logininfor.setStatus(Constants.SUCCESS);
            }
            else if (Constants.LOGIN_FAIL.equals(status))
            {
                logininfor.setStatus(Constants.FAIL);
            }
            SpringUtils.getBean(SysLogininforServiceImpl.class).insertLogininfor(logininfor);
        };
    }
}
