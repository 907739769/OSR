package com.osr.openliststrm.notify.dto;

import java.util.List;

/**
 * 通知路由配置页所需的全部数据，一次请求取齐。
 *
 * @param types    通知类型清单（后端枚举是唯一真相，前端不要再抄一份）
 * @param channels 渠道清单，含「是否支持分人」「是否已配置」这两个页面要用的能力位
 * @param routes   现有路由行
 *
 * @author Jack
 */
public record NotifyMatrix(List<TypeMeta> types, List<ChannelMeta> channels, List<RouteItem> routes) {

    /**
     * @param code  枚举名，落库用
     * @param label 中文名，展示用
     */
    public record TypeMeta(String code, String label) {
    }

    /**
     * @param key                    渠道标识，与 notify_route.channel 一致
     * @param name                   展示名
     * @param supportsDirectDelivery 是否支持按人投递。false 的渠道页面上不展示收件人选项
     * @param configured             是否已完成配置，未配置时页面提示
     */
    public record ChannelMeta(String key, String name, boolean supportsDirectDelivery, boolean configured) {
    }

    /**
     * @param notificationType 通知类型
     * @param channel          渠道标识
     * @param enabled          是否开启
     * @param recipientScope   收件人范围 ADMIN/OWNER/BOTH
     */
    public record RouteItem(String notificationType, String channel, boolean enabled, String recipientScope) {
    }
}
