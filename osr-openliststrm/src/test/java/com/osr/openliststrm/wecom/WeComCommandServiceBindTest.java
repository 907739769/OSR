package com.osr.openliststrm.wecom;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 企微指令入口的身份解析：自动开号、停用拦截、并发兜底。
 * <p>
 * 这几条分支的共同点是「判错了不会报错，只会悄悄放行或悄悄拒绝」，所以单独钉死。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComCommandServiceBindTest {

    @Mock private IWecomUserPlusService wecomUserService;
    @Mock private WeComUserProvisioner provisioner;
    @Mock private OpenlistConfig config;
    /** 「帮助」指令会清会话，不打桩会 NPE */
    @Mock private WeComSessionStore sessionStore;

    @InjectMocks private WeComCommandService service;

    @BeforeEach
    void setUp() {
        when(config.isWeComAutoCreateUser()).thenReturn(true);
    }

    private static WeComInboundMessage help(String fromUser) {
        return new WeComInboundMessage(fromUser, "text", "帮助", null);
    }

    private static WecomUserPlus bind(String wecomUserId, String status) {
        WecomUserPlus bind = new WecomUserPlus();
        bind.setWecomUserid(wecomUserId);
        bind.setSysUserId(100L);
        bind.setStatus(status);
        return bind;
    }

    @Test
    void 未绑定且开了自动开号_就地建号并正常执行指令() {
        when(wecomUserService.getByWecomUserId("zhangsan")).thenReturn(null);
        when(provisioner.provision("zhangsan")).thenReturn(bind("zhangsan", "0"));

        String reply = service.handle(help("zhangsan"));

        verify(provisioner).provision("zhangsan");
        assertNotNull(reply);
        assertTrue(reply.contains("订阅"), "应回帮助文案而不是未绑定提示，实际：" + reply);
    }

    @Test
    void 未绑定且关了自动开号_回未绑定提示并回显UserId() {
        when(config.isWeComAutoCreateUser()).thenReturn(false);
        when(wecomUserService.getByWecomUserId("zhangsan")).thenReturn(null);

        String reply = service.handle(help("zhangsan"));

        verify(provisioner, never()).provision(anyString());
        assertTrue(reply.contains("还没有绑定"), reply);
        assertTrue(reply.contains("zhangsan"), "要回显 UserId 方便交给管理员，实际：" + reply);
    }

    /**
     * 停用是管理员的明确决定。若把「已停用」当成「没绑定」去自动新建，
     * 任何人被停用后再发一条消息就能自己解封。
     */
    @Test
    void 绑定已停用_拒绝且绝不自动新建() {
        when(wecomUserService.getByWecomUserId("zhangsan")).thenReturn(bind("zhangsan", "1"));

        String reply = service.handle(help("zhangsan"));

        verify(provisioner, never()).provision(anyString());
        assertTrue(reply.contains("停用"), reply);
    }

    @Test
    void 已有可用绑定_不重复开号() {
        when(wecomUserService.getByWecomUserId("zhangsan")).thenReturn(bind("zhangsan", "0"));

        service.handle(help("zhangsan"));

        verify(provisioner, never()).provision(anyString());
    }

    /**
     * 并发下同一成员连发两条消息，后一个事务会撞 wecom_userid 唯一索引回滚。
     * 此时重查应拿到先到者建好的绑定，而不是把异常抛给用户。
     */
    @Test
    void 开号撞唯一索引_重查拿到先到者的绑定() {
        when(wecomUserService.getByWecomUserId("zhangsan"))
                .thenReturn(null)
                .thenReturn(bind("zhangsan", "0"));
        when(provisioner.provision("zhangsan")).thenThrow(new RuntimeException("Duplicate entry"));

        String reply = service.handle(help("zhangsan"));

        assertTrue(reply.contains("订阅"), "应正常执行指令，实际：" + reply);
    }

    @Test
    void 开号失败且无既有绑定_回未绑定提示() {
        when(wecomUserService.getByWecomUserId("zhangsan")).thenReturn(null);
        when(provisioner.provision("zhangsan")).thenReturn(null);

        String reply = service.handle(help("zhangsan"));

        assertTrue(reply.contains("还没有绑定"), reply);
    }

    /** 非文本消息（事件、图片）不该触发开号 */
    @Test
    void 非文本消息_不开号也不回复() {
        String reply = service.handle(new WeComInboundMessage("zhangsan", "event", null, "subscribe"));

        verify(provisioner, never()).provision(anyString());
        assertTrue(reply == null, "非文本消息不应回复，实际：" + reply);
    }
}
