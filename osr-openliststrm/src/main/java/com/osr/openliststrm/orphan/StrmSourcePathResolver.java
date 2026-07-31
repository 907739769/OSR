package com.osr.openliststrm.orphan;

import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 从 .strm 文件内容还原网盘源路径。
 * <p>
 * .strm 内容由 {@code StrmServiceImpl} 写入，格式固定为
 * {@code baseUrl + "/d" + encodePath}；encodePath 在开启编码时是
 * {@code URLEncoder.encode(path, UTF_8).replace("+","%20").replace("%2F","/")} 的结果——
 * 本类做这个变换的逆过程。
 */
public final class StrmSourcePathResolver {

    private StrmSourcePathResolver() {
    }

    /**
     * @param strmContent .strm 文件内容
     * @param baseUrl     当前配置的 OpenList 访问地址（config.getOpenListUrl()）
     * @param encoded     是否按编码规则解码（config.getOpenListStrmEncode()）
     * @return 还原出的网盘源路径；内容为空、baseUrl 为空、或前缀不匹配（比如历史文件用的是旧域名）时返回 null
     */
    public static String resolve(String strmContent, String baseUrl, boolean encoded) {
        if (StringUtils.isBlank(strmContent) || StringUtils.isBlank(baseUrl)) {
            return null;
        }
        String prefix = baseUrl + "/d";
        if (!strmContent.startsWith(prefix)) {
            return null;
        }
        String path = strmContent.substring(prefix.length());
        if (!encoded) {
            return path;
        }
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
