package com.osr.openliststrm.tg;

import com.osr.openliststrm.chat.ChatReply;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TgPtCommandHandlerTest {

    @Test
    void 没有按钮_不带键盘() {
        assertNull(TgPtCommandHandler.keyboard(ChatReply.of("hi")));
    }

    @Test
    void 按钮转成带前缀的回调数据_保持行结构() {
        ChatReply reply = ChatReply.of("x", List.of(
                List.of(new ChatReply.Button("补搜", "补搜 3"), new ChatReply.Button("暂停", "暂停 3")),
                List.of(new ChatReply.Button("刷新", "进度 3"))));

        InlineKeyboardMarkup markup = TgPtCommandHandler.keyboard(reply);

        assertEquals(2, markup.getKeyboard().size());
        InlineKeyboardButton first = markup.getKeyboard().get(0).get(0);
        assertEquals("补搜", first.getText());
        assertEquals("pt|补搜 3", first.getCallbackData());
    }

    /**
     * Telegram 的 callback_data 上限 64 字节，超一个整条消息都发不出去——
     * 宁可省掉那个按钮，正文里的手打指令照样能用。中文按 UTF-8 每字 3 字节，很容易超。
     */
    @Test
    void 回调数据超过64字节的按钮被省略_其余照常() {
        String tooLong = "进度 " + "一".repeat(30);
        ChatReply reply = ChatReply.of("x", List.of(
                List.of(new ChatReply.Button("长", tooLong)),
                List.of(new ChatReply.Button("短", "进度 3"))));

        InlineKeyboardMarkup markup = TgPtCommandHandler.keyboard(reply);

        assertEquals(1, markup.getKeyboard().size());
        assertEquals("pt|进度 3", markup.getKeyboard().get(0).get(0).getCallbackData());
    }
}
