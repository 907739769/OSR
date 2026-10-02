package com.osr.openliststrm.pt.search;

import com.osr.openliststrm.pt.subscription.dto.PushSelectedRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资源搜索页「直接下载」的请求：候选字段原样回传（与订阅内手动推送同一份），外加目标下载器。
 * {@code episode} 在这里不用——没有订阅就没有「占哪一集」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourcePushRequest extends PushSelectedRequest {

    /** 推给哪台下载器；必填，不做负载均衡——用户自己挑，结果才可预期 */
    private Integer downloaderId;
}
