package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.CurrentUserService;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.pt.indexer.TorznabClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「系统级配置的写操作限管理员」这道门。
 * <p>
 * 背景：{@code SecurityConfig} 那一层只有 {@code anyRequest().authenticated()}，即<b>只认证不授权</b>，
 * 而 {@code sys_menu.perms} 只驱动前端菜单可见性、不参与后端放行。所以在这道门加上之前，任何一个
 * 登录用户都能改索引器、改下载器、触发删种与转移做种。
 * <p>
 * 用 {@code PtIndexerRestController} 当被测对象而不是造一个假的子类：这里要钉的正是
 * 「从基类<b>继承</b>来的 add/edit/delete 也确实被拦住了」，而继承恰恰是这类机制最容易失效的地方
 * （也正是当初没选 {@code @PreAuthorize} 的理由——注解落不到继承方法上时是完全静默的）。
 */
class AdminOnlyWriteTest {

    @Mock
    private IPtIndexerPlusService indexerService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private TorznabClient torznabClient;

    private PtIndexerRestController controller;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        controller = new PtIndexerRestController();
        inject("service", indexerService);
        inject("currentUserService", currentUserService);
        inject("torznabClient", torznabClient);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 逐级向上找字段：currentUserService 声明在 BaseController、service 在 BaseCrudRestController */
    private void inject(String fieldName, Object value) throws Exception {
        for (Class<?> type = controller.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(controller, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /** 普通登录用户：有 userId，但既不是 1 号也没有 ROLE_admin */
    private void loginAsPlainUser() {
        when(currentUserService.getUserId()).thenReturn(7L);
    }

    /** 超级管理员的第一条判据：userId == 1，与订阅页 canAccessAll 同源 */
    private void loginAsUserOne() {
        when(currentUserService.getUserId()).thenReturn(1L);
    }

    /** 超级管理员的第二条判据：SecurityContext 里带 ROLE_admin（来自 sys_role.role_key） */
    private void loginWithAdminRole() {
        when(currentUserService.getUserId()).thenReturn(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tom", null,
                        List.of(new SimpleGrantedAuthority("ROLE_admin"))));
    }

    private static PtIndexerPlus indexer() {
        PtIndexerPlus entity = new PtIndexerPlus();
        entity.setId(1);
        entity.setName("站点");
        entity.setUrl("https://example.invalid/api");
        return entity;
    }

    // ---------- 拦住写 ----------

    @Test
    void 普通用户新增索引器_被拒且一行都不落库() {
        loginAsPlainUser();

        Result<Void> result = controller.add(indexer());

        assertEquals(403, result.getCode());
        verify(indexerService, never()).save(any());
    }

    @Test
    void 普通用户修改索引器_被拒且不触碰数据库() {
        loginAsPlainUser();

        Result<Void> result = controller.edit(indexer());

        assertEquals(403, result.getCode());
        verify(indexerService, never()).updateById(any());
        // 校验必须排在 getById 之前：edit 会先查旧记录去回填未修改的 apikey，
        // 放到之后等于让未授权调用照样把密文读进内存
        verify(indexerService, never()).getById(any());
    }

    @Test
    void 普通用户删除索引器_被拒() {
        loginAsPlainUser();

        Result<Void> result = controller.delete(1);

        assertEquals(403, result.getCode());
        verify(indexerService, never()).removeById(anyInt());
    }

    /**
     * 连通性测试端点会把<b>已保存的 apikey</b> 填进来再发往请求体里那个调用方指定的 url，
     * 所以它必须在读到 apikey <b>之前</b>就被拦住——否则等于给出一个把密钥送到任意地址的接口。
     */
    @Test
    void 普通用户调用连通性测试_被拒且不去读已保存的apikey() {
        loginAsPlainUser();
        PtIndexerPlus payload = new PtIndexerPlus();
        payload.setId(1);
        payload.setUrl("http://attacker.invalid/collect");

        Result<Void> result = controller.test(payload);

        assertEquals(403, result.getCode());
        verify(indexerService, never()).getById(any());
        verify(torznabClient, never()).testConnection(any());
    }

    @Test
    void 普通用户拉取分类_被拒且不去读已保存的apikey() throws Exception {
        loginAsPlainUser();
        PtIndexerPlus payload = new PtIndexerPlus();
        payload.setId(1);
        payload.setUrl("http://attacker.invalid/collect");

        Result<List<com.osr.openliststrm.pt.indexer.CategoryOption>> result = controller.categories(payload);

        assertEquals(403, result.getCode());
        verify(indexerService, never()).getById(any());
        verify(torznabClient, never()).getCategories(any());
    }

    // ---------- 放行管理员 ----------

    @Test
    void 一号用户新增索引器_放行() {
        loginAsUserOne();
        when(indexerService.save(any())).thenReturn(true);

        Result<Void> result = controller.add(indexer());

        assertEquals(200, result.getCode());
        verify(indexerService).save(any());
    }

    /**
     * 两条判据取或。只留 userId==1 那条的话，把超级管理员换成别的账号（或 1 号用户的
     * request attribute 因为上游吞异常而没写进来）就会被自己的配置页锁在门外——
     * 而配置页恰恰是唯一能修好这件事的地方。
     */
    @Test
    void 带ROLE_admin的非一号用户_同样放行() {
        loginWithAdminRole();
        when(indexerService.save(any())).thenReturn(true);

        Result<Void> result = controller.add(indexer());

        assertEquals(200, result.getCode());
        verify(indexerService).save(any());
    }

    // ---------- 只拦写，不拦读 ----------

    /**
     * 读放开是有意的：这几个页面的详情要供前端渲染，而敏感字段已被 maskSensitiveFields 抹掉。
     * 把读也拦掉会让非管理员打开页面看到一片 403，而他多半只是想看看当前配了哪些索引器。
     */
    @Test
    void 普通用户查看索引器详情_放行且apikey被脱敏() {
        loginAsPlainUser();
        PtIndexerPlus stored = indexer();
        stored.setApiKey("secret-key");
        when(indexerService.getById(1)).thenReturn(stored);

        Result<PtIndexerPlus> result = controller.getById(1);

        assertNotEquals(403, result.getCode());
        assertEquals(200, result.getCode());
        assertEquals(null, result.getData().getApiKey());
    }

    // ---------- 覆盖面 ----------

    /**
     * 上面那组用例钉的是「这道门的机制有效」，这一条钉的是「该装门的地方都装了」。
     * {@code adminOnlyWrite()} 是一行返回 true 的覆写，重构时删掉它不会有任何编译错误、
     * 不会有告警，接口照常 200——只是权限没了，与当初不选 {@code @PreAuthorize} 要避开的
     * 失效方式一模一样。
     */
    @Test
    void 系统级配置的控制器都覆写了adminOnlyWrite() throws Exception {
        List<Class<? extends BaseCrudRestController<?, ?>>> guarded = List.of(
                PtIndexerRestController.class,
                PtDownloaderRestController.class,
                PtMediaServerRestController.class,
                PtCleanRuleRestController.class,
                PtTransferRuleRestController.class,
                // 热门自动订阅：规则上的 source_url 是用户可填的任意 URL 而后端会去 GET 它，
                // 不限权等于给出一个「让服务端往任意地址发请求」的入口
                PtAutoAddRuleRestController.class);

        Method hook = BaseCrudRestController.class.getDeclaredMethod("adminOnlyWrite");
        hook.setAccessible(true);
        for (Class<? extends BaseCrudRestController<?, ?>> type : guarded) {
            Object instance = type.getDeclaredConstructor().newInstance();
            assertEquals(Boolean.TRUE, hook.invoke(instance),
                    type.getSimpleName() + " 的写操作必须限管理员");
        }
    }
}
