package com.osr.openliststrm.pt.search;

import lombok.Data;

import java.util.List;

/** 资源搜索页恢复缓存结果时，重新判哪些作品已订阅的请求 */
@Data
public class ResourceSubscribedRequest {

    /** 结果里识别出的作品；媒体类型按 DTO 侧的大写 TV / MOVIE 原样传 */
    private List<ResourceSearchService.WorkRef> works;
}
