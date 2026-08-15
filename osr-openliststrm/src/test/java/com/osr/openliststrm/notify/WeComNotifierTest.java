package com.osr.openliststrm.notify;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import com.osr.openliststrm.wecom.WeComApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 企微通知渠道：分人投递、未绑定回退、BOTH 档合并投递、未配置静默。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComNotifierTest {

    @Mock private OpenlistConfig config;
    @Mock private WeComApiClient apiClient;
    @Mock private IWecomUserPlusService wecomUserService;

    @InjectMocks private WeComNotifier notifier;

    @BeforeEach
    void setUp() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(config.getWeComToUser()).thenReturn("@all");
    }

    private static WecomUserPlus bind(String wecomUserId) {
        WecomUserPlus bind = new WecomUserPlus();
        bind.setWecomUserid(wecomUserId);
        return bind;
    }

    @Test
    void 未配置企微_不发送() {
        when(apiClient.isConfigured()).thenReturn(false);

        notifier.send(NotificationType.GENERAL, "hello");

        verify(apiClient, never()).sendText(anyString(), anyString());
    }

    @Test
    void 无归属通知_发给默认接收人() {
        notifier.send(NotificationType.GENERAL, "系统告警", NotifyTarget.BROADCAST);

        verify(apiClient).sendText("@all", "系统告警");
    }

    @Test
    void 有归属通知_只发给归属人绑定的企微成员() {
        when(wecomUserService.listEnabledBySysUserId(42L)).thenReturn(List.of(bind("zhangsan")));

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "下载完成", NotifyTarget.owner(42L));

        verify(apiClient).sendText("zhangsan", "下载完成");
    }

    /** 一个 OSR 账号绑了多个企微号时全部投递，企微的 touser 用 | 分隔 */
    @Test
    void 归属人绑定多个企微成员_用竖线拼接() {
        when(wecomUserService.listEnabledBySysUserId(42L))
                .thenReturn(List.of(bind("zhangsan"), bind("zhangsan2")));

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "下载完成", NotifyTarget.owner(42L));

        verify(apiClient).sendText("zhangsan|zhangsan2", "下载完成");
    }

    /**
     * 归属人没绑企微就回退默认接收人，而不是把通知丢掉：丢了没人知道，
     * 多发给管理员至少还有人能看见并去补绑定。
     */
    @Test
    void 归属人未绑定企微_回退默认接收人() {
        when(wecomUserService.listEnabledBySysUserId(42L)).thenReturn(List.of());

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "下载完成", NotifyTarget.owner(42L));

        verify(apiClient).sendText("@all", "下载完成");
    }

    /**
     * 类型过滤已上收到 NotifierManager（查 notify_route 决定发不发），渠道自己不再判断。
     * 这里钉住「渠道对任何类型都照发」——否则等于同一件事在两个地方判两遍，
     * 用户在配置页关掉的类型，渠道里那份残留判断还会再拦一次，排查起来非常费劲。
     */
    @Test
    void 渠道不再自行过滤类型_任何类型都发() {
        notifier.send(NotificationType.EMBY_LIBRARY_SYNC, "已入库");
        notifier.send(NotificationType.DOWNLOAD_FAILED, "失败了");

        verify(apiClient, times(2)).sendText(eq("@all"), anyString());
    }

    // ---------- BOTH 档：归属人 + 默认接收人 ----------

    @Test
    void BOTH档_归属人与默认接收人一起投递() {
        when(wecomUserService.listEnabledBySysUserId(42L)).thenReturn(List.of(bind("zhangsan")));

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.ownerAndDefault(42L));

        verify(apiClient).sendText(eq("zhangsan|@all"), anyString());
    }

    /** 归属人恰好就是默认接收人时要去重，否则同一个会话里会出现两条一样的通知 */
    @Test
    void BOTH档_归属人就是默认接收人时去重() {
        when(config.getWeComToUser()).thenReturn("zhangsan");
        when(wecomUserService.listEnabledBySysUserId(42L)).thenReturn(List.of(bind("zhangsan")));

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.ownerAndDefault(42L));

        verify(apiClient).sendText(eq("zhangsan"), anyString());
    }

    @Test
    void BOTH档_归属人未绑定企微_仍发默认接收人() {
        when(wecomUserService.listEnabledBySysUserId(42L)).thenReturn(List.of());

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.ownerAndDefault(42L));

        verify(apiClient).sendText(eq("@all"), anyString());
    }

    /** 发消息本身失败不能把异常抛回业务主流程 */
    @Test
    void 发送抛异常_内部吞掉() {
        when(apiClient.sendText(anyString(), anyString())).thenThrow(new RuntimeException("boom"));

        notifier.send(NotificationType.GENERAL, "hello");
    }

    /**
     * 历史通知文案是按 Telegram 的 HTML parse_mode 写的，企微 text 消息不解析 HTML，
     * 原样发过去用户会看到一堆尖括号标签。
     */
    @Test
    void 清洗HTML标签_企微收到纯文本() {
        assertEquals("复制任务失败\n路径：/a/b",
                WeComNotifier.toPlainText("<b>复制任务失败</b>\n路径：/a/b"));
    }

    @Test
    void 清洗HTML标签_无标签时原样返回() {
        assertEquals("📌 订阅命中：《三体》", WeComNotifier.toPlainText("📌 订阅命中：《三体》"));
    }

    /** 影视标题里的书名号/尖括号内容不能被当成标签误删 */
    @Test
    void 清洗HTML标签_不误删非标签的尖括号() {
        assertEquals("剩余 <3 集未入库", WeComNotifier.toPlainText("剩余 <3 集未入库"));
    }

    /**
     * 所有动态内容都过了 {@code StringUtils.escapeHtml}（为 TG 的 HTML parse_mode 准备），
     * 企微不解析 HTML，不还原实体的话种子标题里的 & 会显示成 &amp;。
     * PT 片名与组名带 & 相当常见，这是最容易被用户看见的一处。
     */
    @Test
    void 还原HTML实体_不再显示成amp() {
        assertEquals("✅ 下载完成：Tom & Jerry 1080p",
                WeComNotifier.toPlainText("✅ 下载完成：Tom &amp; Jerry 1080p"));
    }

    @Test
    void 还原HTML实体_尖括号实体一并还原() {
        assertEquals("剩余 <3 集未入库", WeComNotifier.toPlainText("剩余 &lt;3 集未入库"));
    }

    /**
     * 先去标签、后解实体，顺序不能换：反过来的话字面的 &amp;lt;b&amp;gt; 会先被还原成 {@code <b>}，
     * 紧接着被去标签那步当成真标签删掉，用户输入的文本凭空少一截。
     */
    @Test
    void 先去标签后解实体_被转义的标签不会被误删() {
        assertEquals("片名里写着 <b> 这三个字", WeComNotifier.toPlainText("片名里写着 &lt;b&gt; 这三个字"));
    }

    @Test
    void 无归属通知_目标传null_按广播处理() {
        notifier.send(NotificationType.GENERAL, "hello", null);

        verify(apiClient).sendText(eq("@all"), any());
    }
}
