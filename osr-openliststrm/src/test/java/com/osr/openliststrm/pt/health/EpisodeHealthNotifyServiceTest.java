package com.osr.openliststrm.pt.health;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EpisodeHealthNotifyServiceTest {

    @Mock
    private EpisodeHealthService healthService;

    @Mock
    private IPtSubscriptionPlusService subscriptionService;

    private EpisodeHealthNotifyService service(boolean enabled, int repeatDays) {
        when(healthService.getOverdueDays()).thenReturn(3);
        return new EpisodeHealthNotifyService(healthService, subscriptionService, enabled, repeatDays);
    }

    private static PtSubscriptionPlus sub(int id, String title, Long owner) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setTitle(title);
        s.setSeason(1);
        s.setOwnerUserId(owner);
        return s;
    }

    private static EpisodeHealthItem item(int episode, EpisodeHealthBucket bucket, Integer overdueDays) {
        return new EpisodeHealthItem(episode, "MISSING", "2026-08-10", overdueDays,
                bucket.name(), EpisodeHealthDiagnosis.AUTO_SEARCH_OFF.name());
    }

    private void given(SubscriptionHealth... healths) {
        when(healthService.scan()).thenReturn(List.of(healths));
        when(subscriptionService.listOverdueNotified()).thenReturn(List.of());
    }

    @Test
    void 只对逾期缺失档提醒_在途与熔断与无日期都不发() {
        // 在途逾期由 LIBRARY_STUCK 覆盖，熔断在转 BLOCKED 那一刻已通知过，
        // 无播出日期连"逾期"都算不出来。这条边界一旦模糊，同一集会从多个渠道各通知一次
        PtSubscriptionPlus s = sub(1, "三体", null);
        given(new SubscriptionHealth(s, List.of(
                item(1, EpisodeHealthBucket.OVERDUE_IN_FLIGHT, 7),
                item(2, EpisodeHealthBucket.BLOCKED, 7),
                item(3, EpisodeHealthBucket.NO_AIR_DATE, null))));

        service(true, 7).notifyOverdue();

        verify(subscriptionService, never()).updateOverdueNotifyState(any(), any(), any());
    }

    @Test
    void 首次逾期立刻提醒并落指纹() {
        PtSubscriptionPlus s = sub(1, "三体", null);
        given(new SubscriptionHealth(s, List.of(
                item(3, EpisodeHealthBucket.OVERDUE_MISSING, 7),
                item(5, EpisodeHealthBucket.OVERDUE_MISSING, 5))));

        service(true, 7).notifyOverdue();

        verify(subscriptionService).updateOverdueNotifyState(eq(1), eq("2:3,5"), any(Date.class));
    }

    @Test
    void 指纹相同且未到重提醒周期时跳过() {
        PtSubscriptionPlus s = sub(1, "三体", null);
        s.setLastOverdueNotifySign("1:3");
        s.setLastOverdueNotifyTime(new Date(System.currentTimeMillis() - 86_400_000L));
        given(new SubscriptionHealth(s, List.of(item(3, EpisodeHealthBucket.OVERDUE_MISSING, 7))));

        service(true, 7).notifyOverdue();

        verify(subscriptionService, never()).updateOverdueNotifyState(any(), any(), any());
    }

    @Test
    void 指纹变了立刻再提醒一次() {
        PtSubscriptionPlus s = sub(1, "三体", null);
        s.setLastOverdueNotifySign("1:3");
        s.setLastOverdueNotifyTime(new Date());
        given(new SubscriptionHealth(s, List.of(
                item(3, EpisodeHealthBucket.OVERDUE_MISSING, 7),
                item(4, EpisodeHealthBucket.OVERDUE_MISSING, 6))));

        service(true, 7).notifyOverdue();

        verify(subscriptionService).updateOverdueNotifyState(eq(1), eq("2:3,4"), any(Date.class));
    }

    @Test
    void 指纹没变但已过重提醒周期时再提醒一次_永远补不上的剧不能就此静音() {
        PtSubscriptionPlus s = sub(1, "三体", null);
        s.setLastOverdueNotifySign("1:3");
        s.setLastOverdueNotifyTime(new Date(System.currentTimeMillis() - 8L * 86_400_000L));
        given(new SubscriptionHealth(s, List.of(item(3, EpisodeHealthBucket.OVERDUE_MISSING, 7))));

        service(true, 7).notifyOverdue();

        verify(subscriptionService).updateOverdueNotifyState(eq(1), eq("1:3"), any(Date.class));
    }

    @Test
    void 通知时间缺失时按该提醒处理_宁可多发也不静默丢() {
        PtSubscriptionPlus s = sub(1, "三体", null);
        s.setLastOverdueNotifySign("1:3");
        s.setLastOverdueNotifyTime(null);
        given(new SubscriptionHealth(s, List.of(item(3, EpisodeHealthBucket.OVERDUE_MISSING, 7))));

        service(true, 7).notifyOverdue();

        verify(subscriptionService).updateOverdueNotifyState(eq(1), eq("1:3"), any(Date.class));
    }

    @Test
    void 缺集补齐后清空指纹_否则下次缺同一批集会被静默吞掉() {
        PtSubscriptionPlus resolved = sub(2, "已补齐", null);
        resolved.setLastOverdueNotifySign("1:3");
        when(healthService.scan()).thenReturn(List.of());
        when(subscriptionService.listOverdueNotified()).thenReturn(List.of(resolved));

        service(true, 7).notifyOverdue();

        verify(subscriptionService).updateOverdueNotifyState(eq(2), isNull(), isNull());
    }

    @Test
    void 关闭开关后不扫也不写() {
        service(false, 7).notifyOverdue();

        verify(healthService, never()).scan();
        verify(subscriptionService, never()).updateOverdueNotifyState(any(), any(), any());
    }

    @Test
    void 同一归属人的多条订阅合并成一条消息_首次启用时不会刷屏() {
        SubscriptionHealth a = new SubscriptionHealth(sub(1, "三体", 7L),
                List.of(item(3, EpisodeHealthBucket.OVERDUE_MISSING, 7)));
        SubscriptionHealth b = new SubscriptionHealth(sub(2, "赛博朋克", 7L),
                List.of(item(1, EpisodeHealthBucket.OVERDUE_MISSING, 4)));

        String msg = service(true, 7).buildMessage(List.of(a, b));

        assertTrue(msg.startsWith("📺 有 2 部剧播出超过 3 天仍未匹配到资源"), msg);
        assertTrue(msg.contains("三体") && msg.contains("赛博朋克"), msg);
        assertTrue(msg.contains("已播出 7 天") && msg.contains("已播出 4 天"), msg);
        assertTrue(msg.contains(EpisodeHealthDiagnosis.AUTO_SEARCH_OFF.getLabel()), msg);
    }

    @Test
    void 指纹带集数前缀_截断后不同集合不会互相冒充() {
        // 长篇动画一季上百集，集号串轻易超过列宽；两批不同的缺集常共享一长串相同前缀
        String many = EpisodeHealthNotifyService.signatureOf(
                java.util.stream.IntStream.rangeClosed(1, 200).boxed().toList());
        String fewer = EpisodeHealthNotifyService.signatureOf(
                java.util.stream.IntStream.rangeClosed(1, 199).boxed().toList());

        assertEquals(255, many.length());
        assertTrue(many.startsWith("200:1,2,3,"), many);
        assertTrue(fewer.startsWith("199:1,2,3,"), fewer);
    }

    @Test
    void 标题里的HTML被转义_一个尖括号就能让整条TG消息发不出去() {
        SubscriptionHealth h = new SubscriptionHealth(sub(1, "Tom & <b>Jerry</b>", null),
                List.of(item(1, EpisodeHealthBucket.OVERDUE_MISSING, 5)));

        String msg = service(true, 7).buildMessage(List.of(h));

        assertTrue(msg.contains("Tom &amp; &lt;b&gt;Jerry&lt;/b&gt;"), msg);
    }
}
