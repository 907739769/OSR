package com.osr.openliststrm.wecom;

import com.osr.openliststrm.chat.ChatUser;
import com.osr.openliststrm.chat.PtChatCommandService;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 企业微信指令入口：解析成员身份、把菜单点击还原成指令，再交给 {@link PtChatCommandService}。
 * <p>
 * <b>身份</b>：每条指令都先经 {@code wecom_user} 把企微 UserId 换成 OSR 用户，未绑定直接拒绝。
 * 建出来的订阅归属该 OSR 用户，查询/操作也只能碰到自己的订阅——这是「分不同用户」的落点。
 * <p>
 * 指令本身（订阅、进度、补搜……）与渠道无关，和 Telegram Bot 共用 {@link PtChatCommandService}。
 * 本类只返回文案、不负责发送，因此可以脱离企微服务端直接单测。
 *
 * @author Jack
 */
@Slf4j
@Service
public class WeComCommandService {

    /**
     * 菜单 EventKey → 指令文本的白名单。key 必须与 {@link WeComMenuDefinition} 里建菜单用的
     * key 一致，改动时两边同步，并在企微侧重新同步一次菜单。
     * <p>
     * 「订阅」「订阅电影」刻意映射成不带关键词的指令：走到搜索那一步后会按媒体类型
     * 回一句「请带上剧名/片名，例如：…」，正好充当菜单点不了输入框时的引导语，
     * 不必为此单独写分支。
     */
    static final Map<String, String> MENU_COMMANDS = Map.of(
            "cmd:mysubs", "我的订阅",
            "cmd:downloading", "下载中",
            "cmd:recent", "最近入库",
            "cmd:sub_tv", "订阅",
            "cmd:sub_movie", "订阅电影",
            "cmd:help", "帮助",
            "cmd:whoami", "我的账号");

    @Autowired
    private IWecomUserPlusService wecomUserService;
    @Autowired
    private WeComUserProvisioner provisioner;
    @Autowired
    private OpenlistConfig config;
    @Autowired
    private PtChatCommandService chatCommandService;
    @Autowired
    private WeComApiClient apiClient;

    /**
     * 处理一条入站消息，返回要回复给该成员的文本。
     *
     * @return 回复文案；返回 null 表示这条消息不需要回复（非文本消息、事件等）
     */
    public String handle(WeComInboundMessage message) {
        if (message == null || !message.isActionable()) {
            return null;
        }
        String wecomUserId = message.fromUser();
        WecomUserPlus bind = resolveBind(wecomUserId);
        if (bind == null) {
            // 把 UserId 回显出来：管理员建绑定时要填的正是这个值，让用户直接抄给管理员，
            // 省掉「去企微后台翻通讯录找自己的 UserId」这一步
            return "你还没有绑定 OSR 账号，无法使用订阅功能。\n请把你的企业微信 UserId 提供给管理员完成绑定：\n"
                    + wecomUserId;
        }
        if (!bind.isEnabled()) {
            // 已停用是管理员的明确决定，绝不能被自动开号覆盖掉
            return "你的绑定已被管理员停用，无法使用订阅功能。";
        }
        String command = toCommand(message);
        if (command == null) {
            // 菜单 key 不在白名单里：多半是企微侧菜单没重新同步，还挂着旧版本
            log.warn("收到无法识别的企微菜单事件，EventKey={}", message.eventKey());
            return "该菜单项已失效，请让管理员到「企业微信用户」页面重新同步应用菜单。";
        }
        // 异常由 PtChatCommandService#handle 兜住并转成提示文案
        return dispatch(bind, command);
    }

    /**
     * 把入站消息归一成一条指令文本：文本消息取其内容，菜单点击按 EventKey 查表。
     * <p>
     * 菜单走白名单映射而不是「EventKey 直接当指令文本用」，有两个原因：菜单是写死在
     * 企微服务器上的，与代码里的指令文案解耦后，改文案不必重新同步菜单；同时白名单本身
     * 限定了菜单能触发的动作范围，不会因为 EventKey 可控而变成任意指令入口。
     *
     * @return 指令文本；菜单 key 不认识时返回 null
     */
    private String toCommand(WeComInboundMessage message) {
        if (message.isText()) {
            return message.content().trim();
        }
        return MENU_COMMANDS.get(message.eventKey().trim());
    }

    /**
     * 取该成员的绑定；没有且开了自动开号就就地建一个。
     * <p>
     * 已存在的绑定<b>一律原样返回</b>（含已停用的），由调用方判断是否可用：
     * 停用是管理员的明确决定，若在这里当成「没绑定」去自动新建，等于任何人被停用后
     * 再发一条消息就能自己解封。
     *
     * @return 绑定；未绑定且未开自动开号（或开号失败）时返回 null
     */
    private WecomUserPlus resolveBind(String wecomUserId) {
        WecomUserPlus bind = wecomUserService.getByWecomUserId(wecomUserId);
        if (bind != null || !config.isWeComAutoCreateUser()) {
            return bind;
        }
        try {
            return provisioner.provision(wecomUserId);
        } catch (Exception e) {
            // 并发下同一成员连发两条消息，后一个事务会撞 wecom_userid 唯一索引回滚，
            // 重查一次就能拿到先到者建好的绑定
            WecomUserPlus existing = wecomUserService.getByWecomUserId(wecomUserId);
            if (existing != null) {
                return existing;
            }
            log.warn("为企微成员[{}]自动开号失败：{}", wecomUserId, e.getMessage());
            return null;
        }
    }

    /** 把企微绑定换成渠道无关的 {@link ChatUser}，指令本身交给 {@link PtChatCommandService} */
    private String dispatch(WecomUserPlus bind, String text) {
        String wecomUserId = bind.getWecomUserid();
        String accountText = "企业微信 UserId：" + wecomUserId
                + "\nOSR 账号：" +(StringUtils.isNotBlank(bind.getSysUserName())
                ? bind.getSysUserName() : ("#" + bind.getSysUserId()));
        ChatUser user = new ChatUser("wecom:" + wecomUserId, bind.getSysUserId(), accountText,
                later -> apiClient.sendText(wecomUserId, later));
        // 企微文本消息没有按钮，只取正文；ChatReply 约定正文自给自足
        return chatCommandService.handle(user, text).text();
    }
}
