package com.osr.openliststrm.pt.subscription;

import com.osr.common.core.domain.entity.SysUser;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.system.service.ISysUserService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 归属筛选：只在可见范围内收窄；选项里「我的」「公共」恒在，其他人按名字排；名字取不到时写明已删除。
 */
class SubscriptionOwnerServiceTest {

    private static PtSubscriptionPlus sub(Long owner) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setOwnerUserId(owner);
        return s;
    }

    @Test
    void 内存判定与各筛选值() {
        assertTrue(SubscriptionOwnerService.matches(sub(2L), null, 1L));
        assertTrue(SubscriptionOwnerService.matches(sub(1L), "mine", 1L));
        assertFalse(SubscriptionOwnerService.matches(sub(2L), "mine", 1L));
        assertFalse(SubscriptionOwnerService.matches(sub(null), "mine", null), "取不到当前用户时我的为空");
        assertTrue(SubscriptionOwnerService.matches(sub(null), "public", 1L));
        assertTrue(SubscriptionOwnerService.matches(sub(2L), "2", 1L));
        assertFalse(SubscriptionOwnerService.matches(sub(3L), "2", 1L));
        assertTrue(SubscriptionOwnerService.matches(sub(3L), "胡写", 1L), "认不出的筛选值当作不筛");
    }

    @Test
    void 管理员选项含其他用户_名字排序_删除的用户写明() {
        IPtSubscriptionPlusService subs = mock(IPtSubscriptionPlusService.class);
        ISysUserService users = mock(ISysUserService.class);
        when(subs.listMaps(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(row(null, 3), row(1L, 5), row(7L, 2), row(8L, 4)));
        SysUser bob = new SysUser();
        bob.setLoginName("bob");
        SysUser amy = new SysUser();
        amy.setUserName("Amy");
        when(users.selectUserById(7L)).thenReturn(bob);
        when(users.selectUserById(8L)).thenReturn(amy);
        when(users.selectUserById(9L)).thenReturn(null);

        List<SubscriptionOwnerService.OwnerOption> options = new SubscriptionOwnerService(subs, users).options(true, 1L);

        assertEquals(List.of("mine", "public", "8", "7"), options.stream().map(SubscriptionOwnerService.OwnerOption::value).toList());
        assertEquals(5, options.get(0).count());
        assertEquals(3, options.get(1).count());
        assertEquals("Amy", options.get(2).label());

        PtSubscriptionPlus gone = sub(9L);
        PtSubscriptionPlus pub = sub(null);
        PtSubscriptionPlus mine = sub(1L);
        new SubscriptionOwnerService(subs, users).fillOwnerNames(List.of(gone, pub, mine), 1L);
        assertEquals("已删除用户#9", gone.getOwnerName());
        assertNull(pub.getOwnerName());
        assertNull(mine.getOwnerName(), "自己的订阅不标名字");
    }

    private static Map<String, Object> row(Long owner, long cnt) {
        Map<String, Object> m = new HashMap<>();
        m.put("owner_user_id", owner);
        m.put("cnt", cnt);
        return m;
    }
}
