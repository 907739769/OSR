package com.osr.openliststrm.pt.upgrade;

import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一个版本的质量画像：洗版判定的最小信息集。
 * <p>
 * 不可变。既用来表示"库里现在躺的那个版本"（由
 * {@code pt_subscription_episode.quality} 反序列化而来），也用来表示"候选种子"
 * （由 {@link TorrentInfo} 转换而来）。两边同构，比较逻辑因此是对称的，
 * 不必为"实体 vs 候选"写两套。
 * </p>
 * <p>
 * 刻意<b>不包含</b>体积、做种数、促销状态——那些不是画质。详见
 * {@link com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus#getQualityPriority()}。
 * </p>
 *
 * @param resolution   分辨率，如 2160p；解析不出为 null
 * @param source       媒介来源，如 REMUX/BluRay/WEBDL；解析不出为 null
 * @param releaseGroup 发布组；解析不出为 null
 * @param tags         质量标签（HDR10/ATMOS/10BIT…），恒不为 null
 * @author Jack
 */
@Slf4j
public record QualityProfile(String resolution, String source, String releaseGroup, List<String> tags) {

    private static final String KEY_RESOLUTION = "resolution";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_GROUP = "group";
    private static final String KEY_TAGS = "tags";

    public QualityProfile {
        resolution = StringUtils.trimToNull(resolution);
        source = StringUtils.trimToNull(source);
        releaseGroup = StringUtils.trimToNull(releaseGroup);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** 从候选种子的本地解析结果构造。调用方须先跑过 {@code SubscriptionEngine#fillParsed} */
    public static QualityProfile from(TorrentInfo torrent) {
        return new QualityProfile(torrent.getParsedResolution(), torrent.getParsedSource(),
                torrent.getParsedReleaseGroup(), torrent.getParsedTags());
    }

    /** 直接从本地解析结果构造，供"已入库版本"这一侧使用（它没有 TorrentInfo，只有下载记录的标题） */
    public static QualityProfile from(MediaInfo info) {
        return new QualityProfile(info.getResolution(), info.getSource(), info.getReleaseGroup(),
                collectTags(info));
    }

    /**
     * 质量标签 = {@code MediaInfo.tags} <b>加上</b>视频编码与音频编码，按大写去重。
     * <p>
     * 编码必须并进来：extractor 按 Resolution → Codec → SourceAndGroup 顺序跑，
     * {@code CodecExtractor} 会先把 "Atmos"/"H265"/"DTS-HD" 匹进 codec 字段并从标题里抹掉，
     * 等 {@code SourceAndGroupExtractor} 再扫 TAGS 时已经找不到它们了。
     * </p>
     * <p>
     * <b>这是全项目唯一的标签口径</b>，「候选种子」（{@code SubscriptionEngine#fillParsed}）与
     * 「已入库版本」（{@code SubscriptionService#applyQualityBaseline}）两侧都必须走这里。
     * 两边各写一份的话，画像不同源，洗版比较的结果就没有意义了。
     * </p>
     */
    public static List<String> collectTags(MediaInfo info) {
        Map<String, String> byUpper = new LinkedHashMap<>();
        if (info.getTags() != null) {
            for (String tag : info.getTags()) {
                if (StringUtils.isNotBlank(tag)) {
                    byUpper.putIfAbsent(tag.toUpperCase(Locale.ROOT), tag);
                }
            }
        }
        for (String codec : new String[]{info.getVideoCodec(), info.getAudioCodec()}) {
            if (StringUtils.isNotBlank(codec)) {
                byUpper.putIfAbsent(codec.toUpperCase(Locale.ROOT), codec);
            }
        }
        return new ArrayList<>(byUpper.values());
    }

    /**
     * 从 {@code pt_subscription_episode.quality} 的 JSON 反序列化。
     * <p>
     * 为 null/空白、格式损坏、或不是 JSON 对象时一律返回 {@code null}，由调用方按
     * "无质量基线"处理——一条脏数据不该让整轮洗版扫描挂掉，更不该被当成"基线极差"
     * 而触发一次盲目升级。
     * </p>
     */
    public static QualityProfile fromJson(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            JSONObject obj = JSONObject.parseObject(json);
            if (obj == null) {
                return null;
            }
            List<String> tags = new ArrayList<>();
            if (obj.getJSONArray(KEY_TAGS) != null) {
                for (Object tag : obj.getJSONArray(KEY_TAGS)) {
                    if (tag != null && StringUtils.isNotBlank(tag.toString())) {
                        tags.add(tag.toString());
                    }
                }
            }
            return new QualityProfile(obj.getString(KEY_RESOLUTION), obj.getString(KEY_SOURCE),
                    obj.getString(KEY_GROUP), tags);
        } catch (Exception e) {
            log.warn("质量画像不是合法 JSON，按无基线处理：{}", e.getMessage());
            return null;
        }
    }

    /** 序列化成落库用的 JSON */
    public String toJson() {
        JSONObject obj = new JSONObject();
        obj.put(KEY_RESOLUTION, resolution);
        obj.put(KEY_SOURCE, source);
        obj.put(KEY_GROUP, releaseGroup);
        obj.put(KEY_TAGS, tags);
        return obj.toJSONString();
    }

    /** 标签命中判定：整词相等、大小写不敏感，与 {@code TorrentFilterEngine} 同一口径 */
    public boolean hasTag(String tag) {
        for (String own : tags) {
            if (own != null && own.equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    /** 标签集合的大写形式，供去重/集合运算 */
    public Set<String> upperTags() {
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            if (StringUtils.isNotBlank(tag)) {
                result.add(tag.toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** 前端展示用的一行摘要，如 {@code 2160p / REMUX / HDR10+ATMOS / CHDBits} */
    public String describe() {
        List<String> parts = new ArrayList<>();
        parts.add(resolution == null ? "未知分辨率" : resolution);
        if (source != null) {
            parts.add(source);
        }
        if (!tags.isEmpty()) {
            parts.add(String.join("+", tags));
        }
        if (releaseGroup != null) {
            parts.add(releaseGroup);
        }
        return String.join(" / ", parts);
    }
}
