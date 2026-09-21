package com.osr.openliststrm.rename.config;

import java.util.List;
import java.util.Map;

/**
 * 重命名文件名模板配置：读取/校验/保存，统一了原来分散在
 * MediaRenameProcessor 和 RenameTaskRestController 里的两份重复常量。
 */
public interface IRenameTemplateConfigService {

    String CONFIG_KEY = "rename.filename.template";

    String DEFAULT_TEMPLATE = "{{ title }} {% if year %} ({{ year }}) {% endif %}/{% if season %}Season {{ season }}/{% endif %}{{ title }} {% if year and not season %} ({{ year }}) {% endif %}{% if season %}S{{ season }}{% endif %}{% if episode %}E{{ episode }}{% endif %}{% if resolution %} - {{ resolution }}{% endif %}{% if source %}.{{ source }}{% endif %}{% if videoCodec %}.{{ videoCodec }}{% endif %}{% if audioCodec %}.{{ audioCodec }}{% endif %}{% if tags is not empty %}.{{ tags|join('.') }}{% endif %}{% if releaseGroup %}-{{ releaseGroup }}{% endif %}.{{ extension }}";

    /**
     * 获取当前生效的模板：优先读 sys_config，取不到时 fallback 到 DEFAULT_TEMPLATE
     */
    String getTemplate();

    /** 预览样例的 key：电影样例 */
    String SAMPLE_MOVIE = "movie";

    /** 预览样例的 key：剧集样例 */
    String SAMPLE_TV = "tv";

    /**
     * 分别用内置的电影样例与剧集样例试渲染模板，不落库；返回 {movie: ..., tv: ...}。
     * 供前端"实时预览"高频调用，不走 TMDb，纯本地渲染。任一份渲染失败抛 IllegalArgumentException。
     * <p>
     * 要两份是因为模板里全是 {@code {% if season %}} 这类分支：只有一份「带季集」的样例时，
     * 电影那半边分支永远预览不到，而那恰恰是最容易写错的一半。
     */
    Map<String, String> previewSamples(String template);

    /**
     * 模板里可用的变量清单，直接由 MediaInfo 转成的渲染上下文生成（见 PebbleRenderer#contextOf），
     * 样例值取自剧集样例。
     */
    List<TemplateVariable> templateVariables();

    /**
     * 校验（两份样例都要渲染得出来）+ 保存到 sys_config + 刷新缓存。
     * 校验失败抛 IllegalArgumentException，不写库。
     */
    void saveTemplate(String template);
}
