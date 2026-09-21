package com.osr.openliststrm.notify;

import com.osr.common.exception.ServiceException;
import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import com.osr.openliststrm.mybatisplus.service.INotifyRoutePlusService;
import com.osr.openliststrm.notify.dto.NotifyMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifyRouteServiceTest {

    @Mock private INotifyRoutePlusService routeTable;
    @Mock private INotifier tg;
    @Mock private INotifier wecom;

    /** 记录「当前是否在事务里」，用来断言写库发生在事务内、缓存失效发生在事务外 */
    private final AtomicBoolean inTx = new AtomicBoolean();
    private final TransactionOperations tx = new TransactionOperations() {
        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            inTx.set(true);
            try {
                return action.doInTransaction(null);
            } finally {
                inTx.set(false);
            }
        }
    };

    private NotifyRouteService service;

    @BeforeEach
    void setUp() {
        when(tg.channelKey()).thenReturn("TELEGRAM");
        when(tg.displayName()).thenReturn("Telegram");
        when(tg.supportsDirectDelivery()).thenReturn(false);
        when(tg.isConfigured()).thenReturn(true);
        when(wecom.channelKey()).thenReturn("WECOM");
        when(wecom.displayName()).thenReturn("企业微信");
        when(wecom.supportsDirectDelivery()).thenReturn(true);
        when(wecom.isConfigured()).thenReturn(true);
        service = new NotifyRouteService(routeTable, List.of(wecom, tg), tx);
    }

    private static NotifyRoutePlus row(String type, String channel, boolean enabled, String scope) {
        NotifyRoutePlus r = new NotifyRoutePlus();
        r.setNotificationType(type);
        r.setChannel(channel);
        r.setEnabled(enabled ? "1" : "0");
        r.setRecipientScope(scope);
        return r;
    }

    private static NotifyMatrix.RouteItem item(String type, String channel, boolean enabled, String scope) {
        return new NotifyMatrix.RouteItem(type, channel, enabled, scope);
    }

    private static NotifyMatrix.RouteItem cell(NotifyMatrix m, String type, String channel) {
        return m.routes().stream()
                .filter(r -> r.notificationType().equals(type) && r.channel().equals(channel))
                .findFirst().orElseThrow();
    }

    // ---------- 配置页数据 ----------

    /**
     * 缺行格子的默认值必须等于分发时缺行的真实行为。原先前端补的是「仅管理员」，
     * 而企微缺行时实际是原样投递（= 仅订阅人），用户一保存缺集提醒就改成了只发管理员。
     */
    @Test
    void 矩阵_缺行格子按真实发送行为补默认值() {
        when(routeTable.list()).thenReturn(List.of());

        NotifyMatrix m = service.matrix();

        NotifyMatrix.RouteItem wecomCell = cell(m, "EPISODE_OVERDUE", "WECOM");
        assertTrue(wecomCell.enabled());
        assertEquals(NotifyRoutePlus.SCOPE_OWNER, wecomCell.recipientScope());
        NotifyMatrix.RouteItem tgCell = cell(m, "EPISODE_OVERDUE", "TELEGRAM");
        assertTrue(tgCell.enabled());
        assertEquals(NotifyRoutePlus.SCOPE_ADMIN, tgCell.recipientScope());
    }

    @Test
    void 矩阵_补齐全部格子且已有行原样返回() {
        when(routeTable.list()).thenReturn(List.of(
                row("GENERAL", "WECOM", false, NotifyRoutePlus.SCOPE_BOTH)));

        NotifyMatrix m = service.matrix();

        assertEquals(NotificationType.values().length * 2, m.routes().size());
        NotifyMatrix.RouteItem kept = cell(m, "GENERAL", "WECOM");
        assertFalse(kept.enabled());
        assertEquals(NotifyRoutePlus.SCOPE_BOTH, kept.recipientScope());
        // 渠道按 key 排序，与请求顺序无关
        assertEquals(List.of("TELEGRAM", "WECOM"), m.channels().stream().map(NotifyMatrix.ChannelMeta::key).toList());
    }

    @Test
    void 矩阵_类型带说明与紧急标记() {
        when(routeTable.list()).thenReturn(List.of());

        NotifyMatrix.TypeMeta failed = service.matrix().types().stream()
                .filter(t -> t.code().equals("DOWNLOAD_FAILED")).findFirst().orElseThrow();

        assertTrue(failed.urgent());
        assertFalse(failed.description().isBlank());
    }

    // ---------- 保存 ----------

    @Test
    @SuppressWarnings("unchecked")
    void 保存_只查一次库_新行插入_变化的行更新_没变的不动() {
        NotifyRoutePlus unchanged = row("GENERAL", "TELEGRAM", true, NotifyRoutePlus.SCOPE_ADMIN);
        NotifyRoutePlus changed = row("GENERAL", "WECOM", true, NotifyRoutePlus.SCOPE_OWNER);
        when(routeTable.list()).thenReturn(List.of(unchanged, changed));

        service.saveAll(List.of(
                item("GENERAL", "TELEGRAM", true, NotifyRoutePlus.SCOPE_ADMIN),
                item("GENERAL", "WECOM", false, NotifyRoutePlus.SCOPE_OWNER),
                item("HR_STATE", "WECOM", true, NotifyRoutePlus.SCOPE_BOTH)));

        verify(routeTable, times(1)).list();
        ArgumentCaptor<Collection<NotifyRoutePlus>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(routeTable).saveBatch(saved.capture());
        assertEquals(List.of("HR_STATE"), saved.getValue().stream().map(NotifyRoutePlus::getNotificationType).toList());
        ArgumentCaptor<Collection<NotifyRoutePlus>> updated = ArgumentCaptor.forClass(Collection.class);
        verify(routeTable).updateBatchById(updated.capture());
        assertEquals(1, updated.getValue().size());
        assertEquals("0", new ArrayList<>(updated.getValue()).get(0).getEnabled());
    }

    @Test
    void 保存_写库发生在事务里() {
        when(routeTable.list()).thenReturn(List.of());
        List<Boolean> seen = new ArrayList<>();
        when(routeTable.saveBatch(anyCollection())).thenAnswer(inv -> {
            seen.add(inTx.get());
            return true;
        });

        service.saveAll(List.of(item("GENERAL", "WECOM", true, NotifyRoutePlus.SCOPE_OWNER)));

        assertEquals(List.of(true), seen);
    }

    @Test
    void 保存_非法渠道_一行都不写() {
        assertThrows(ServiceException.class, () -> service.saveAll(List.of(
                item("GENERAL", "WECOM", true, NotifyRoutePlus.SCOPE_OWNER),
                item("GENERAL", "NOPE", true, NotifyRoutePlus.SCOPE_ADMIN))));

        verify(routeTable, never()).saveBatch(anyCollection());
        verify(routeTable, never()).updateBatchById(anyCollection());
    }

    @Test
    void 保存_非法收件人范围_按仅管理员落库() {
        when(routeTable.list()).thenReturn(List.of());

        service.saveAll(List.of(item("GENERAL", "WECOM", true, "WHATEVER")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NotifyRoutePlus>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(routeTable).saveBatch(saved.capture());
        assertEquals(NotifyRoutePlus.SCOPE_ADMIN, saved.getValue().iterator().next().getRecipientScope());
    }

    // ---------- 缓存 ----------

    @Test
    void 缓存_保存后失效_下次查询重新读库() {
        when(routeTable.list()).thenReturn(List.of(row("GENERAL", "WECOM", true, NotifyRoutePlus.SCOPE_OWNER)));
        service.find(NotificationType.GENERAL, "WECOM");
        service.find(NotificationType.GENERAL, "WECOM");
        verify(routeTable, times(1)).list();

        service.saveAll(List.of(item("GENERAL", "WECOM", false, NotifyRoutePlus.SCOPE_OWNER)));
        service.find(NotificationType.GENERAL, "WECOM");

        // 1 次首查 + 1 次保存时读全表 + 1 次失效后重建
        verify(routeTable, times(3)).list();
    }

    /**
     * 读者在保存之前开始查库、在失效之后才写回缓存：不做代数校验的话，旧数据会被灌回缓存，
     * 用户关掉的通知一直照发到下次保存。
     */
    @Test
    void 缓存_重建期间发生失效_旧数据不写回缓存() {
        NotifyRoutePlus stale = row("GENERAL", "WECOM", true, NotifyRoutePlus.SCOPE_OWNER);
        NotifyRoutePlus fresh = row("GENERAL", "WECOM", false, NotifyRoutePlus.SCOPE_OWNER);
        AtomicBoolean first = new AtomicBoolean(true);
        when(routeTable.list()).thenAnswer(inv -> {
            if (first.getAndSet(false)) {
                // 模拟：这次读到的是旧数据，读完、写回缓存之前恰好有人保存并失效了缓存
                service.invalidate();
                return List.of(stale);
            }
            return List.of(fresh);
        });

        assertTrue(service.find(NotificationType.GENERAL, "WECOM").enabledOn());
        assertFalse(service.find(NotificationType.GENERAL, "WECOM").enabledOn());
    }

    // ---------- 发送测试 ----------

    @Test
    void 发送测试_未配置_不调渠道直接给原因() {
        when(tg.isConfigured()).thenReturn(false);

        String reason = service.sendTest("TELEGRAM");

        assertTrue(reason.contains("尚未配置"));
        verify(tg, never()).sendTest(anyString());
    }

    @Test
    void 发送测试_成功返回null_失败原样带回原因() {
        when(tg.sendTest(anyString())).thenReturn(null);
        when(wecom.sendTest(anyString())).thenReturn("企业微信返回错误 81013：user & party & tag all invalid");

        assertNull(service.sendTest("TELEGRAM"));
        assertEquals("企业微信返回错误 81013：user & party & tag all invalid", service.sendTest("WECOM"));
    }

    @Test
    void 发送测试_渠道抛异常_转成原因而不是500() {
        when(tg.sendTest(anyString())).thenThrow(new IllegalStateException("boom"));

        assertTrue(service.sendTest("TELEGRAM").contains("boom"));
    }

    @Test
    void 发送测试_未知渠道_报错() {
        assertThrows(ServiceException.class, () -> service.sendTest("NOPE"));
        verify(tg, never()).sendTest(any());
    }
}
