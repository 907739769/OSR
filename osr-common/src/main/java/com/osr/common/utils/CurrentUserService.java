package com.osr.common.utils;

import com.osr.common.core.domain.entity.SysUser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class CurrentUserService {

    public String getLoginName() {
        try {
            return CurrentUserContext.getLoginName();
        } catch (Exception e) {
            return "anonymous";
        }
    }

    public Long getUserId() {
        try {
            return CurrentUserContext.getUserId();
        } catch (Exception e) {
            return 0L;
        }
    }

    public SysUser getUser() {
        try {
            SysUser user = CurrentUserContext.getCurrentUser();
            if (user != null) return user;
        } catch (Exception e) { /* */ }
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                Object user = request.getAttribute("currentUser");
                if (user instanceof SysUser sysUser) {
                    return sysUser;
                }
            }
        } catch (Exception e) { /* */ }
        return null;
    }
}
