package com.osr.openliststrm.chat;

import java.util.List;

/**
 * 一条指令的回复：正文 + 可选的快捷按钮。
 * <p>
 * <b>正文必须自给自足</b>：企微文本消息没有按钮，只看 {@link #text}；按钮只是 TG 上的快捷方式，
 * 它能做的事用户照着正文手打指令也必须能做到。
 *
 * @param text    回复正文
 * @param buttons 按钮，外层是行、内层是一行里的按钮；没有按钮时为空表
 */
public record ChatReply(String text, List<List<Button>> buttons) {

    public static ChatReply of(String text) {
        return new ChatReply(text, List.of());
    }

    public static ChatReply of(String text, List<List<Button>> buttons) {
        return new ChatReply(text, buttons == null ? List.of() : buttons);
    }

    /**
     * 一个按钮。
     *
     * @param label   按钮上的字
     * @param command 点下去等价于发送的那条指令文本，走与手打指令完全相同的解析
     */
    public record Button(String label, String command) {
    }
}
