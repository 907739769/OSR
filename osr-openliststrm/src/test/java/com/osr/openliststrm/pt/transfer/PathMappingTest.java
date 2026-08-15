package com.osr.openliststrm.pt.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 保存路径映射。
 * <p>
 * 这里的每条用例对应一种"映射错了会怎样"：目标下载器在错误的路径下找不到数据，
 * 校验必然不过，转移被撤销。所以边界（前缀不能匹配到同名兄弟目录）必须钉死。
 * </p>
 *
 * @author Jack
 */
class PathMappingTest {

    @Test
    void 未配置映射时原样返回() {
        PathMapping mapping = PathMapping.parse(null);
        assertEquals(0, mapping.size());
        assertEquals("/downloads/movies/x", mapping.apply("/downloads/movies/x"));
    }

    @Test
    void 前缀命中时替换前缀其余部分保持原样() {
        PathMapping mapping = PathMapping.parse("[{\"from\":\"/downloads\",\"to\":\"/data/downloads\"}]");
        assertEquals("/data/downloads/movies/x", mapping.apply("/downloads/movies/x"));
    }

    @Test
    void 前缀刚好等于整个路径时也命中() {
        PathMapping mapping = PathMapping.parse("[{\"from\":\"/downloads\",\"to\":\"/data/dl\"}]");
        assertEquals("/data/dl", mapping.apply("/downloads"));
    }

    /**
     * 这条是本类存在的主要理由：按子串替换会把 {@code /downloads-old} 也改掉，
     * 得到一个看起来对、实际不存在的路径，而症状（校验 0%）与"完全没配映射"一模一样。
     */
    @Test
    void 前缀不能匹配到同名兄弟目录() {
        PathMapping mapping = PathMapping.parse("[{\"from\":\"/downloads\",\"to\":\"/data/downloads\"}]");
        assertEquals("/downloads-old/movies", mapping.apply("/downloads-old/movies"));
    }

    @Test
    void 取第一条命中的规则() {
        PathMapping mapping = PathMapping.parse(
                "[{\"from\":\"/downloads/tv\",\"to\":\"/tv\"},{\"from\":\"/downloads\",\"to\":\"/all\"}]");
        assertEquals("/tv/x", mapping.apply("/downloads/tv/x"));
        assertEquals("/all/movie/y", mapping.apply("/downloads/movie/y"));
    }

    @Test
    void 结尾斜杠不影响匹配() {
        PathMapping mapping = PathMapping.parse("[{\"from\":\"/downloads/\",\"to\":\"/data/downloads/\"}]");
        assertEquals("/data/downloads/movies", mapping.apply("/downloads/movies"));
    }

    /** 一条都不命中时原样返回，而不是返回空串——空串会让目标端把种子下到下载器的默认目录去 */
    @Test
    void 一条都不命中时原样返回() {
        PathMapping mapping = PathMapping.parse("[{\"from\":\"/downloads\",\"to\":\"/data\"}]");
        assertEquals("/mnt/media/x", mapping.apply("/mnt/media/x"));
    }

    /**
     * 配置格式错误退化成"不映射"而不是抛异常：整条规则不该因为一处配置错误而停摆，
     * 失败的那次转移会在记录里留下明确的路径与进度，比一条解析异常更能指出问题。
     */
    @Test
    void 配置非法时退化成不映射() {
        PathMapping mapping = PathMapping.parse("这不是 JSON");
        assertEquals(0, mapping.size());
        assertEquals("/downloads/x", mapping.apply("/downloads/x"));
    }

    @Test
    void 缺少from或to的条目被忽略() {
        PathMapping mapping = PathMapping.parse("[{\"from\":\"/downloads\"},{\"to\":\"/data\"}]");
        assertEquals(0, mapping.size());
    }
}
