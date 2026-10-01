package com.osr.openliststrm.pt.search;

import lombok.Data;

import java.util.List;

/** 资源搜索页的搜索请求 */
@Data
public class ResourceSearchRequest {

    /** 关键词，原样发给各索引器 */
    private String keyword;

    /** 只搜这几个站点；空表示全部启用中的 */
    private List<Integer> indexerIds;
}
