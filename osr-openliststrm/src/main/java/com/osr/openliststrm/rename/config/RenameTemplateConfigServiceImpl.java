package com.osr.openliststrm.rename.config;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.rename.render.PebbleRenderer;
import com.osr.system.domain.SysConfig;
import com.osr.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RenameTemplateConfigServiceImpl implements IRenameTemplateConfigService {

    /**
     * 变量的中文说明。**只是说明，不是清单**：清单由 MediaInfo 的渲染上下文生成，
     * 这里漏登记一个字段的后果只是页面上那个变量没有说明，不会缺变量。
     */
    private static final Map<String, String> VARIABLE_LABELS = Map.ofEntries(
            Map.entry("originalName", "原始文件名"),
            Map.entry("title", "标题（优先 TMDb 中文名）"),
            Map.entry("originalTitle", "从文件名抽出的原始标题"),
            Map.entry("englishTitle", "从文件名抽出的英文标题"),
            Map.entry("year", "年份"),
            Map.entry("season", "季号，电影为空"),
            Map.entry("episode", "集号，电影为空"),
            Map.entry("episodeEnd", "区间结尾集号，如 E01-03 里的 03；非区间为空"),
            Map.entry("tmdbId", "TMDb ID"),
            Map.entry("episodeName", "单集标题"),
            Map.entry("episodeTmdbId", "单集 TMDb ID"),
            Map.entry("episodePlot", "单集简介"),
            Map.entry("episodeAiredDate", "单集播出日期"),
            Map.entry("episodeRating", "单集评分"),
            Map.entry("episodeDirector", "单集导演"),
            Map.entry("episodeWriter", "单集编剧"),
            Map.entry("episodeGuestStars", "单集客串演员"),
            Map.entry("episodeStillPath", "单集剧照路径"),
            Map.entry("resolution", "分辨率"),
            Map.entry("videoCodec", "视频编码"),
            Map.entry("audioCodec", "音频编码"),
            Map.entry("source", "片源"),
            Map.entry("tags", "特效标签（列表，常用 tags|join('.')）"),
            Map.entry("releaseGroup", "发布组"),
            Map.entry("extension", "扩展名（不含点）"),
            Map.entry("genreIds", "TMDb 类型 ID（列表）"),
            Map.entry("originalLanguage", "原始语言"),
            Map.entry("originCountries", "出品国家/地区（列表）")
    );

    /** 页面上先展示的常用变量，其余收在「更多」里 */
    private static final Set<String> COMMON_VARIABLES = Set.of(
            "title", "year", "season", "episode", "resolution",
            "source", "videoCodec", "audioCodec", "tags", "releaseGroup", "extension");

    /** TMDb 原始响应，一份 images 就 26KB，放进文件名没有意义 */
    private static final Set<String> HIDDEN_VARIABLES = Set.of("metadata");

    @Autowired
    ISysConfigService sysConfigService;

    private final PebbleRenderer renderer = new PebbleRenderer();

    @Override
    public String getTemplate() {
        String value = sysConfigService.selectConfigByKey(CONFIG_KEY);
        return StringUtils.isNotBlank(value) ? value : DEFAULT_TEMPLATE;
    }

    @Override
    public Map<String, String> previewSamples(String template) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(SAMPLE_MOVIE, render(buildMovieSample(), template, "电影"));
        out.put(SAMPLE_TV, render(buildTvSample(), template, "剧集"));
        return out;
    }

    private String render(MediaInfo sample, String template, String kind) {
        try {
            return renderer.render(sample, template);
        } catch (Exception e) {
            throw new IllegalArgumentException("模板渲染失败（" + kind + "样例）：" + rootMessage(e));
        }
    }

    /** PebbleRenderer 把异常包成 RuntimeException，外层 message 是 "java.lang.xxx: ..."，取最里层的给人看 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    @Override
    public List<TemplateVariable> templateVariables() {
        List<TemplateVariable> out = new ArrayList<>();
        PebbleRenderer.contextOf(buildTvSample()).forEach((name, value) -> {
            if (HIDDEN_VARIABLES.contains(name)) {
                return;
            }
            out.add(new TemplateVariable(name, VARIABLE_LABELS.get(name), display(value), COMMON_VARIABLES.contains(name)));
        });
        return out;
    }

    private static String display(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    @Override
    public void saveTemplate(String template) {
        // 校验失败抛异常，必须在写库之前；两份样例都要渲染得出来
        previewSamples(template);
        Optional<SysConfig> existing = findExisting();
        if (existing.isPresent()) {
            SysConfig config = existing.get();
            config.setConfigValue(template);
            sysConfigService.updateConfig(config);
        } else {
            SysConfig config = new SysConfig();
            config.setConfigName("重命名文件名模板");
            config.setConfigKey(CONFIG_KEY);
            config.setConfigValue(template);
            config.setConfigType("N");
            sysConfigService.insertConfig(config);
        }
        sysConfigService.resetConfigCache();
    }

    private Optional<SysConfig> findExisting() {
        SysConfig query = new SysConfig();
        query.setConfigKey(CONFIG_KEY);
        return sysConfigService.selectConfigList(query).stream()
                .filter(c -> CONFIG_KEY.equals(c.getConfigKey()))
                .findFirst();
    }

    /** 电影样例：没有季集，模板里 {% if season %} 的另一半分支要靠它才预览得到 */
    static MediaInfo buildMovieSample() {
        MediaInfo info = new MediaInfo("Sample.Movie.2026.2160p.WEB-DL.H265.DDP5.1-EXAMPLE.mkv");
        info.setTitle("示例电影");
        info.setOriginalTitle("示例电影");
        info.setEnglishTitle("Sample Movie");
        info.setYear("2026");
        info.setTmdbId("100001");
        info.setResolution("2160p");
        info.setSource("WEB-DL");
        info.setVideoCodec("H265");
        info.setAudioCodec("DDP5.1");
        info.setTags(Arrays.asList("HDR"));
        info.setReleaseGroup("EXAMPLE");
        info.setExtension("mkv");
        info.setGenreIds(Arrays.asList("18"));
        info.setOriginalLanguage("zh");
        info.setOriginCountries(Arrays.asList("CN"));
        return info;
    }

    /** 剧集样例：尽量把每个字段都填上，变量清单里的样例值取的就是它 */
    static MediaInfo buildTvSample() {
        MediaInfo info = new MediaInfo("Sample.Show.S01E03.2026.1080p.WEB-DL.H265.AAC-EXAMPLE.mkv");
        info.setTitle("示例剧集");
        info.setOriginalTitle("示例剧集");
        info.setEnglishTitle("Sample Show");
        info.setYear("2026");
        info.setSeason("1");
        info.setEpisode("3");
        info.setTmdbId("200002");
        info.setEpisodeName("第三集的标题");
        info.setEpisodeTmdbId("300003");
        info.setEpisodePlot("单集剧情简介");
        info.setEpisodeAiredDate("2026-01-15");
        info.setEpisodeRating("8.5");
        info.setEpisodeDirector("导演甲");
        info.setEpisodeWriter("编剧乙");
        info.setEpisodeGuestStars("客串丙, 客串丁");
        info.setEpisodeStillPath("/sample-still.jpg");
        info.setResolution("1080p");
        info.setSource("WEB-DL");
        info.setVideoCodec("H265");
        info.setAudioCodec("AAC");
        info.setTags(Arrays.asList("HDR"));
        info.setReleaseGroup("EXAMPLE");
        info.setExtension("mkv");
        info.setGenreIds(Arrays.asList("18"));
        info.setOriginalLanguage("zh");
        info.setOriginCountries(Arrays.asList("CN"));
        return info;
    }
}
