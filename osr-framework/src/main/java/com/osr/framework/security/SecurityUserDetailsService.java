package com.osr.framework.security;

import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.enums.UserStatus;
import com.osr.common.exception.user.UserBlockedException;
import com.osr.common.exception.user.UserDeleteException;
import com.osr.system.service.ISysMenuService;
import com.osr.system.service.ISysRoleService;
import com.osr.system.service.ISysUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@Service
public class SecurityUserDetailsService implements UserDetailsService {
    @Autowired
    private ISysUserService userService;
    @Autowired
    private ISysRoleService roleService;
    @Autowired
    private ISysMenuService menuService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userService.selectUserByLoginName(username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        if (UserStatus.DELETED.getCode().equals(user.getDelFlag())) {
            throw new UserDeleteException();
        }
        if (UserStatus.DISABLE.getCode().equals(user.getStatus())) {
            throw new UserBlockedException();
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        Set<String> roleKeys = roleService.selectRoleKeys(user.getUserId());
        for (String role : roleKeys) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        Set<String> perms = menuService.selectPermsByUserId(user.getUserId());
        for (String perm : perms) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attrs.getRequest();
            request.setAttribute("currentUser", user);
        } catch (Exception e) {
        }
        return new org.springframework.security.core.userdetails.User(
                user.getLoginName(), user.getPassword(), authorities);
    }
}
