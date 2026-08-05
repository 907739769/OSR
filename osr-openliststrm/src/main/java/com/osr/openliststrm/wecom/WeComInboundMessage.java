package com.osr.openliststrm.wecom;

/**
 * 解密后的企微入站消息。只保留指令交互用得到的字段——企微报文里还有 MsgId、CreateTime 等，
 * 当前没有去重与时序需求，取了也只是摆着。
 *
 * @param fromUser 发消息的企微成员 UserId
 * @param msgType  消息类型，text / event / image / voice ...
 * @param content  文本内容，仅 msgType=text 时有值
 * @param event    事件类型，仅 msgType=event 时有值（如 subscribe、click）
 * @author Jack
 */
public record WeComInboundMessage(String fromUser, String msgType, String content, String event) {

    public static final String TYPE_TEXT = "text";

    /** 是否是可以当作指令处理的文本消息 */
    public boolean isText() {
        return TYPE_TEXT.equalsIgnoreCase(msgType) && content != null && !content.isBlank();
    }
}
