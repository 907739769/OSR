package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.CurrentUserService;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 0a40bf50 修复的行为：建订阅补搜触发异常不应影响 subscribe() 返回成功，
 * 防止未来重构把 subscribe() 内的两个 try 块又合并回去导致误报建订阅失败。
 */
class PtSubscriptionRestControllerTest {

    @Mock
    private SubscriptionService subscriptionBiz;

    @Mock
    private SubscriptionSearchOnCreateTrigger searchOnCreateTrigger;

    /** subscribe() 要取当前登录用户写订阅归属，不打桩会在 BaseController.getUserId() 处 NPE */
    @Mock
    private CurrentUserService currentUserService;

    /** 基类 BaseCrudRestController 的 service 字段，归属校验要靠它查订阅 */
    @Mock
    private IPtSubscriptionPlusService subscriptionService;

    private PtSubscriptionRestController controller;

    @BeforeAll
    static void registerTableInfo() {
        // 检查列表 wrapper 生成的 SQL 要解析实体 lambda，那份缓存平时由 Mapper 注册时建立，纯单测里手动建一次
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(PtSubscriptionPlus.class.getName());
        TableInfoHelper.initTableInfo(assistant, PtSubscriptionPlus.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        controller = new PtSubscriptionRestController();
        inject("subscriptionBiz", subscriptionBiz);
        inject("searchOnCreateTrigger", searchOnCreateTrigger);
        inject("currentUserService", currentUserService);
        inject("service", subscriptionService);
        when(currentUserService.getUserId()).thenReturn(7L);
    }

    /** 逐级向上找字段：currentUserService 声明在 BaseController 上，不在控制器自身 */
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

    private static PtSubscriptionPlus activeSub(Integer id) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setStatus(SubscriptionService.STATUS_ACTIVE);
        return sub;
    }

    @Test
    void subscribe_补搜触发抛异常_建订阅仍返回成功() throws Exception {
        PtSubscriptionPlus sub = activeSub(10);
        when(subscriptionBiz.subscribe(any(SubscribeRequest.class))).thenReturn(sub);
        doThrow(new RuntimeException("boom")).when(searchOnCreateTrigger).triggerAsync(anyInt());

        Result<Void> result = controller.subscribe(new SubscribeRequest());

        assertEquals(200, result.getCode());
        verify(searchOnCreateTrigger).triggerAsync(10);
    }

    @Test
    void subscribe_新订阅为ACTIVE_触发一次补搜() throws Exception {
        PtSubscriptionPlus sub = activeSub(11);
        when(subscriptionBiz.subscribe(any(SubscribeRequest.class))).thenReturn(sub);

        Result<Void> result = controller.subscribe(new SubscribeRequest());

        assertEquals(200, result.getCode());
        verify(searchOnCreateTrigger).triggerAsync(11);
    }

    @Test
    void subscribe_新订阅非ACTIVE_不触发补搜() throws Exception {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(12);
        sub.setStatus("COMPLETED");
        when(subscriptionBiz.subscribe(any(SubscribeRequest.class))).thenReturn(sub);

        Result<Void> result = controller.subscribe(new SubscribeRequest());

        assertEquals(200, result.getCode());
        verify(searchOnCreateTrigger, org.mockito.Mockito.never()).triggerAsync(anyInt());
    }

    /** 归属于 ownerId 的订阅 */
    private static PtSubscriptionPlus ownedSub(Integer id, Long ownerId) {
        PtSubscriptionPlus sub = activeSub(id);
        sub.setOwnerUserId(ownerId);
        return sub;
    }

    /**
     * 管理员(userId=1)能访问别人名下的订阅。
     * <p>
     * 曾经因为 CurrentUserService.getUserId() 恒返回 null（它读的 ThreadLocal
     * 从来没有过滤器填充），管理员被判成未登录用户，网页端只看得到 owner_user_id
     * IS NULL 的历史订阅——企微里建的订阅全部消失，而接口照常 200、日志照常干净。
     */
    @Test
    void 管理员_能访问他人名下的订阅() {
        when(currentUserService.getUserId()).thenReturn(1L);
        when(subscriptionService.getById(50)).thenReturn(ownedSub(50, 999L));

        assertEquals(200, controller.getById(50).getCode());
    }

    @Test
    void 普通用户_不能访问他人名下的订阅() {
        when(currentUserService.getUserId()).thenReturn(9L);
        when(subscriptionService.getById(50)).thenReturn(ownedSub(50, 999L));

        assertEquals(500, controller.getById(50).getCode());
    }

    @Test
    void 普通用户_能访问自己的订阅() {
        when(currentUserService.getUserId()).thenReturn(9L);
        when(subscriptionService.getById(50)).thenReturn(ownedSub(50, 9L));

        assertEquals(200, controller.getById(50).getCode());
    }

    /** 无归属的历史订阅对所有人可见，否则升级后老数据会从非管理员的列表里整批消失 */
    @Test
    void 普通用户_能访问无归属的公共订阅() {
        when(currentUserService.getUserId()).thenReturn(9L);
        when(subscriptionService.getById(50)).thenReturn(ownedSub(50, null));

        assertEquals(200, controller.getById(50).getCode());
    }

    /** 取不到当前用户时不能放行有归属的订阅 */
    @Test
    void 取不到当前用户_不能访问有归属的订阅() {
        when(currentUserService.getUserId()).thenReturn(null);
        when(subscriptionService.getById(50)).thenReturn(ownedSub(50, 999L));

        assertEquals(500, controller.getById(50).getCode());
    }

    /**
     * 归属人必须来自当前登录用户，且要盖掉请求体里带的值——否则任何人都能构造一个
     * ownerUserId 把订阅挂到别人名下，那个人会收到一堆自己没订过的下载通知。
     */
    @Test
    void subscribe_归属人取当前登录用户_忽略请求体传入的值() throws Exception {
        when(subscriptionBiz.subscribe(any(SubscribeRequest.class))).thenReturn(activeSub(13));
        SubscribeRequest request = new SubscribeRequest();
        request.setOwnerUserId(999L);

        controller.subscribe(request);

        ArgumentCaptor<SubscribeRequest> captor = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(subscriptionBiz).subscribe(captor.capture());
        assertEquals(7L, captor.getValue().getOwnerUserId());
    }

    // ---------- 列表筛选与排序 ----------

    /** 「只看有缺集」与「按缺集数排」必须是同一份口径：已播出的缺失/阻塞集，未播出的不算 */
    @Test
    void 列表_只看有缺集与按缺集排序_共用已播出口径() {
        PtSubscriptionPlus query = new PtSubscriptionPlus();
        query.setHasMissing("1");
        query.setSortBy("missing");

        String sql = controller.buildQueryWrapper(query).getCustomSqlSegment();

        assertTrue(sql.contains("SELECT sub_id FROM pt_subscription_episode WHERE state IN ('MISSING','BLOCKED')"
                + " AND (air_date IS NULL OR air_date <= CURDATE())"), sql);
        assertTrue(sql.contains("ORDER BY (SELECT COUNT(*) FROM pt_subscription_episode e WHERE e.sub_id = pt_subscription.id"
                + " AND e.state IN ('MISSING','BLOCKED') AND (e.air_date IS NULL OR e.air_date <= CURDATE())) DESC"), sql);
        // 非管理员的归属条件不能因为换成 QueryWrapper 外壳而丢掉
        assertTrue(sql.contains("owner_user_id"), sql);
    }

    @Test
    void 列表_未指定缺集筛选与排序_不带子查询() {
        String sql = controller.buildQueryWrapper(new PtSubscriptionPlus()).getCustomSqlSegment();

        assertFalse(sql.contains("pt_subscription_episode"), sql);
    }

    // ---------- 批量开关自动补搜 ----------

    /** 普通用户批量操作时，他人名下的订阅要被滤掉，只把自己的与公共的交给业务层 */
    @Test
    void 批量自动补搜_滤掉无权操作的订阅() {
        when(currentUserService.getUserId()).thenReturn(9L);
        when(subscriptionService.listByIds(anyCollection()))
                .thenReturn(List.of(ownedSub(1, 9L), ownedSub(2, 999L), ownedSub(3, null)));
        when(subscriptionBiz.setAutoSearchBatch(anyList(), anyBoolean())).thenReturn(2);

        Result<Integer> result = controller.batchAutoSearch("1,2,3", true);

        assertEquals(200, result.getCode());
        verify(subscriptionBiz).setAutoSearchBatch(eq(List.of(1, 3)), eq(true));
    }

    @Test
    void 批量自动补搜_全部无权_不调业务层() {
        when(currentUserService.getUserId()).thenReturn(9L);
        when(subscriptionService.listByIds(anyCollection())).thenReturn(List.of(ownedSub(2, 999L)));

        Result<Integer> result = controller.batchAutoSearch("2", false);

        assertEquals(500, result.getCode());
        verify(subscriptionBiz, never()).setAutoSearchBatch(anyList(), anyBoolean());
    }

    @Test
    void 批量自动补搜_id格式错误_返回提示而不是抛异常() {
        Result<Integer> result = controller.batchAutoSearch("1,abc", true);

        assertEquals(500, result.getCode());
        verify(subscriptionBiz, never()).setAutoSearchBatch(anyList(), anyBoolean());
    }
}
