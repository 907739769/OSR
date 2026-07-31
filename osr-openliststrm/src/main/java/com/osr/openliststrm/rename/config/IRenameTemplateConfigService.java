package com.osr.openliststrm.rename.config;

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

    /**
     * 用内置示例 MediaInfo 试渲染模板，不落库。渲染失败抛 IllegalArgumentException。
     * 供前端"实时预览"高频调用，不走 TMDb，纯本地渲染。
     */
    String previewRender(String template);

    /**
     * 校验（复用 previewRender）+ 保存到 sys_config + 刷新缓存。
     * 校验失败抛 IllegalArgumentException，不写库。
     */
    void saveTemplate(String template);
}
