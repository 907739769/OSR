package com.osr.openliststrm.wecom;

/**
 * 解密后的企微入站消息。只保留指令交互用得到的字段——企微报文里还有 MsgId、CreateTime 等，
 * 当前没有去重与时序需求，取了也只是摆着。
 *
 * @param fromUser 发消息的企微成员 UserId
 * @param msgType  消息类型，text / event / image / voice ...
 * @param content  文本内容，仅 msgType=text 时有值
 * @param event    事件类型，仅 msgType=event 时有值（如 click、view、subscribe）
 * @param eventKey 事件 key，菜单点击（event=click）时是建菜单时设的 key，其余事件多为空
 * @author Jack
 */
public record WeComInboundMessage(String fromUser, String msgType, String content, String event, String eventKey) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_EVENT = "event";

    /** 菜单点击事件。菜单的另一种类型 view 由企微直接跳转，不回调过来 */
    public static final String EVENT_CLICK = "click";

    /** 是否是可以当作指令处理的文本消息 */
    public boolean isText() {
        return TYPE_TEXT.equalsIgnoreCase(msgType) && content != null && !content.isBlank();
    }

    /**
     * 是否是菜单点击事件。这类事件带着 EventKey，等价于用户发了一条对应的指令文本，
     * 所以和 {@link #isText()} 一样要交给指令服务处理。
     */
    public boolean isMenuClick() {
        return TYPE_EVENT.equalsIgnoreCase(msgType)
                && EVENT_CLICK.equalsIgnoreCase(event)
                && eventKey != null && !eventKey.isBlank();
    }

    /** 是否需要交给指令服务处理 */
    public boolean isActionable() {
        return isText() || isMenuClick();
    }
}
