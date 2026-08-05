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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 菜单点击事件的路由。
 * <p>
 * 菜单点击带不了参数，只能靠 EventKey 查表还原成一条指令；查不到时必须给出可行动的提示，
 * 否则用户点了没反应、也不知道是菜单过期还是功能坏了。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComMenuClickTest {

    @Mock private IWecomUserPlusService wecomUserService;
    @Mock private WeComUserProvisioner provisioner;
    @Mock private OpenlistConfig config;
    @Mock private WeComSessionStore sessionStore;

    @InjectMocks private WeComCommandService service;

    @BeforeEach
    void setUp() {
        WecomUserPlus bind = new WecomUserPlus();
        bind.setWecomUserid("zhangsan");
        bind.setSysUserId(100L);
        bind.setStatus("0");
        when(wecomUserService.getByWecomUserId("zhangsan")).thenReturn(bind);
    }

    private static WeComInboundMessage click(String eventKey) {
        return new WeComInboundMessage("zhangsan", "event", null, "click", eventKey);
    }

    @Test
    void 点击帮助菜单_返回帮助文案() {
        String reply = service.handle(click("cmd:help"));

        assertTrue(reply != null && reply.contains("可用指令"), "实际：" + reply);
    }

    /**
     * 「订阅剧集」映射成不带关键词的订阅指令，走到 startSearch 后回引导语——
     * 菜单点击没法输入剧名，这是刻意的设计，不是漏了分支。
     */
    @Test
    void 点击订阅剧集菜单_返回输入引导语() {
        String reply = service.handle(click("cmd:sub_tv"));

        assertTrue(reply != null && reply.contains("请带上要搜索的名字"), "实际：" + reply);
    }

    @Test
    void 点击订阅电影菜单_返回输入引导语() {
        String reply = service.handle(click("cmd:sub_movie"));

        assertTrue(reply != null && reply.contains("请带上要搜索的名字"), "实际：" + reply);
    }

    /** 企微侧菜单没重新同步时会出现旧 key，提示要能指向解决办法 */
    @Test
    void 未知菜单key_提示重新同步菜单() {
        String reply = service.handle(click("cmd:not_exists"));

        assertTrue(reply != null && reply.contains("同步"), "实际：" + reply);
    }

    /** view 类型由企微直接跳转，不会回调；subscribe 等事件也不该被当指令处理 */
    @Test
    void 非click事件_不处理() {
        assertNull(service.handle(new WeComInboundMessage("zhangsan", "event", null, "subscribe", null)));
        assertNull(service.handle(new WeComInboundMessage("zhangsan", "event", null, "view", "http://x")));
    }

    @Test
    void click事件但EventKey为空_不处理() {
        assertNull(service.handle(new WeComInboundMessage("zhangsan", "event", null, "click", "")));
        assertNull(service.handle(new WeComInboundMessage("zhangsan", "event", null, "click", null)));
    }

    /** 图片、语音等消息仍然忽略 */
    @Test
    void 非文本非事件消息_不处理() {
        assertNull(service.handle(new WeComInboundMessage("zhangsan", "image", null, null, null)));
        assertNull(service.handle(new WeComInboundMessage("zhangsan", "voice", null, null, null)));
    }
}
