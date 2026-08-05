package com.osr.openliststrm.controller.api;

import com.osr.common.annotation.Anonymous;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.wecom.WeComApiClient;
import com.osr.openliststrm.wecom.WeComCommandService;
import com.osr.openliststrm.wecom.WeComCrypto;
import com.osr.openliststrm.wecom.WeComInboundMessage;
import com.osr.openliststrm.wecom.WeComXmlParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 企业微信「接收消息」回调入口。地址填 {@code http(s)://<你的域名>/api/openliststrm/wecom/callback}。
 * <p>
 * <b>匿名端点</b>：请求来自企微服务器，不可能带 OSR 的 JWT。安全性由企微的签名校验
 * （{@link WeComCrypto#verifySignature}）+ AES 解密 + receiveid 比对三重保证，
 * 三者都要用到只有配置方知道的 Token/AESKey/corpid，签名不过直接拒绝。
 * <p>
 * <b>为什么不用被动回复</b>：企微要求 5 秒内响应，而建订阅要串行调 TMDb 搜索 + 详情 +
 * 媒体库对账，很容易超时导致企微重试（同一条指令被执行多次）。这里立即回空串表示
 * 「已收到、无需回复」，处理完再用应用消息主动推回结果。
 *
 * @author Jack
 * @date 2026-08-05
 */
@Slf4j
@RestController
@RequestMapping("/api/openliststrm/wecom")
@Anonymous
public class WeComCallbackController {

    @Autowired
    private OpenlistConfig config;

    @Autowired
    private WeComApiClient apiClient;

    @Autowired
    private WeComCommandService commandService;

    /**
     * 懒加载而不是字段初始化器：字段初始化在本 bean 构造时就执行，会隐式依赖
     * virtualScheduledExecutor 先于本类完成初始化；同时也让本类无法脱离 Spring 上下文
     * 构造（单测里 SpringUtils.beanFactory 为 null，连 new 都会失败）。
     * 单例 bean 的 getBean 只是一次 map 查找，放在调用处开销可忽略。
     */
    private TaskScheduler scheduler() {
        return SpringUtils.getBean("virtualScheduledExecutor");
    }

    /**
     * URL 有效性验证。企微在后台保存回调配置时会 GET 一次，
     * 要求把 echostr 解密后原样返回明文。
     */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@RequestParam("msg_signature") String msgSignature,
                         @RequestParam("timestamp") String timestamp,
                         @RequestParam("nonce") String nonce,
                         @RequestParam("echostr") String echostr) {
        if (!apiClient.isCallbackConfigured()) {
            log.warn("收到企微回调 URL 验证请求，但企微回调参数(Token/AESKey)尚未配置完整");
            return "";
        }
        if (!WeComCrypto.verifySignature(config.getWeComToken(), msgSignature, timestamp, nonce, echostr)) {
            log.warn("企微回调 URL 验证签名不匹配，请检查后台配置的 Token 是否与企微一致");
            return "";
        }
        try {
            return WeComCrypto.decrypt(config.getWeComAesKey(), config.getWeComCorpId(), echostr);
        } catch (IllegalArgumentException e) {
            log.warn("企微回调 URL 验证解密失败：{}", e.getMessage());
            return "";
        }
    }

    /**
     * 接收成员消息。校验并解密后异步处理，立即返回空串。
     * <p>
     * 返回空串是企微协议里「已收到、不做被动回复」的约定值；返回别的内容
     * （比如一段错误提示）会被企微当成格式非法的回复报文，在会话里报错。
     */
    @PostMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String receive(@RequestParam("msg_signature") String msgSignature,
                          @RequestParam("timestamp") String timestamp,
                          @RequestParam("nonce") String nonce,
                          @RequestBody String body) {
        if (!apiClient.isCallbackConfigured()) {
            return "";
        }
        WeComInboundMessage message;
        try {
            String encrypt = WeComXmlParser.extractEncrypt(body);
            if (!WeComCrypto.verifySignature(config.getWeComToken(), msgSignature, timestamp, nonce, encrypt)) {
                log.warn("企微回调消息签名不匹配，已丢弃");
                return "";
            }
            String plain = WeComCrypto.decrypt(config.getWeComAesKey(), config.getWeComCorpId(), encrypt);
            message = WeComXmlParser.parseMessage(plain);
        } catch (IllegalArgumentException e) {
            log.warn("企微回调报文处理失败：{}", e.getMessage());
            return "";
        }
        if (!message.isActionable()) {
            // 图片、语音、以及 click 之外的事件（subscribe/view 等）一律忽略
            return "";
        }
        // Threads.wrap 保证 traceId 跨线程传播，否则指令处理的日志与本次请求对不上号
        scheduler().schedule(Threads.wrap(() -> handleAsync(message)), Instant.now());
        return "";
    }

    /** 异步执行指令并把结果推回给发送者。任何异常都在这里终结，不能让调度线程带着异常退出 */
    private void handleAsync(WeComInboundMessage message) {
        try {
            String reply = commandService.handle(message);
            if (StringUtils.isNotBlank(reply)) {
                apiClient.sendText(message.fromUser(), reply);
            }
        } catch (Exception e) {
            log.error("处理企微指令异常，fromUser={}", message.fromUser(), e);
            apiClient.sendText(message.fromUser(), "处理失败，请稍后重试或到网页端操作。");
        }
    }
}
