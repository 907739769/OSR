package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.CurrentUserService;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRulePlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRulePlusService;
import com.osr.openliststrm.pt.transfer.TorrentTransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转移做种两个控制器的权限门与保存前校验。
 * <p>
 * 清除失败记录等于解除「停止重试」的闸门，放开后那批种子会重新开始转移（最后一步是删源端种子），
 * 必须与改规则、立即执行同级限管理员；保存前校验则把「跑一轮才报错」的配置当场拦下。
 * </p>
 *
 * @author Jack
 */
class PtTransferControllersTest {

    @Mock private IPtTransferRecordPlusService recordService;
    @Mock private IPtTransferRulePlusService ruleService;
    @Mock private IPtDownloaderPlusService downloaderService;
    @Mock private TorrentTransferService transferService;
    @Mock private CurrentUserService currentUserService;

    private PtTransferRecordRestController recordController;
    private PtTransferRuleRestController ruleController;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        recordController = new PtTransferRecordRestController();
        inject(recordController, "recordService", recordService);
        inject(recordController, "currentUserService", currentUserService);

        ruleController = new PtTransferRuleRestController();
        inject(ruleController, "service", ruleService);
        inject(ruleController, "transferService", transferService);
        inject(ruleController, "downloaderService", downloaderService);
        inject(ruleController, "currentUserService", currentUserService);

        when(downloaderService.getById(1)).thenReturn(downloader(1, "QBITTORRENT"));
        when(downloaderService.getById(2)).thenReturn(downloader(2, "TRANSMISSION"));
        when(ruleService.save(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static PtDownloaderPlus downloader(int id, String type) {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(id);
        d.setType(type);
        return d;
    }

    private static PtTransferRulePlus rule() {
        PtTransferRulePlus rule = new PtTransferRulePlus();
        rule.setId(10);
        rule.setName("qb搬tr");
        rule.setSourceDownloaderId(1);
        rule.setTargetDownloaderId(2);
        rule.setEnabled("1");
        return rule;
    }

    private void loginAsPlainUser() {
        when(currentUserService.getUserId()).thenReturn(7L);
    }

    private void loginAsAdmin() {
        when(currentUserService.getUserId()).thenReturn(1L);
    }

    // ---------- 清除失败记录 ----------

    @Test
    void 普通用户清除失败记录_被拒且一行都不删() {
        loginAsPlainUser();

        Result<Integer> result = recordController.clearFailed(null);

        assertEquals(403, result.getCode());
        verify(recordService, never()).remove(any(Wrapper.class));
    }

    @Test
    void 管理员清除失败记录_放行() {
        loginAsAdmin();
        when(recordService.count(any(Wrapper.class))).thenReturn(3L);

        Result<Integer> result = recordController.clearFailed(10);

        assertEquals(200, result.getCode());
        assertEquals(3, result.getData());
        verify(recordService).remove(any(Wrapper.class));
    }

    // ---------- 保存前校验 ----------

    @Test
    void 源与目标是同一个下载器_保存被拒() {
        loginAsAdmin();
        PtTransferRulePlus rule = rule();
        rule.setTargetDownloaderId(1);

        Result<Void> result = ruleController.add(rule);

        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("同一个"), result.getMessage());
        verify(ruleService, never()).save(any());
    }

    @Test
    void 源是Transmission_保存被拒() {
        loginAsAdmin();
        PtTransferRulePlus rule = rule();
        rule.setSourceDownloaderId(2);
        rule.setTargetDownloaderId(1);

        Result<Void> result = ruleController.add(rule);

        assertTrue(result.getMessage().contains("Transmission"), result.getMessage());
        verify(ruleService, never()).save(any());
    }

    @Test
    void 路径映射不是合法JSON_保存被拒() {
        loginAsAdmin();
        PtTransferRulePlus rule = rule();
        rule.setPathMapping("{from:/downloads");

        Result<Void> result = ruleController.add(rule);

        assertTrue(result.getMessage().contains("路径映射"), result.getMessage());
        verify(ruleService, never()).save(any());
    }

    @Test
    void 体积下限大于上限_保存被拒() {
        loginAsAdmin();
        PtTransferRulePlus rule = rule();
        rule.setMinSizeGb(new BigDecimal("50"));
        rule.setMaxSizeGb(new BigDecimal("10"));

        Result<Void> result = ruleController.add(rule);

        assertTrue(result.getMessage().contains("体积"), result.getMessage());
        verify(ruleService, never()).save(any());
    }

    @Test
    void 配置合法_正常保存() {
        loginAsAdmin();
        PtTransferRulePlus rule = rule();
        rule.setPathMapping("[{\"from\":\"/downloads\",\"to\":\"/data/downloads\"}]");

        Result<Void> result = ruleController.add(rule);

        assertEquals(200, result.getCode());
        verify(ruleService).save(any());
    }

    // ---------- 立即执行 ----------

    @Test
    void 规则正在执行时_立即执行直接拒绝不再排队() throws Exception {
        loginAsAdmin();
        when(ruleService.getById(10)).thenReturn(rule());
        when(transferService.isRunning(10)).thenReturn(true);

        Result<Map<String, Object>> result = ruleController.run(10);

        assertTrue(result.getMessage().contains("正在执行"), result.getMessage());
        verify(transferService, never()).runRule(any());
    }

    @Test
    void 普通用户立即执行_被拒() throws Exception {
        loginAsPlainUser();

        Result<Map<String, Object>> result = ruleController.run(10);

        assertEquals(403, result.getCode());
        verify(transferService, never()).runRule(any());
    }
}
