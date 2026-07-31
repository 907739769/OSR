package com.osr.openliststrm.rename.config;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.rename.render.PebbleRenderer;
import com.osr.system.domain.SysConfig;
import com.osr.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;

@Service
public class RenameTemplateConfigServiceImpl implements IRenameTemplateConfigService {

    @Autowired
    ISysConfigService sysConfigService;

    private final PebbleRenderer renderer = new PebbleRenderer();

    @Override
    public String getTemplate() {
        String value = sysConfigService.selectConfigByKey(CONFIG_KEY);
        return StringUtils.isNotBlank(value) ? value : DEFAULT_TEMPLATE;
    }

    @Override
    public String previewRender(String template) {
        try {
            return renderer.render(buildSampleMediaInfo(), template);
        } catch (Exception e) {
            throw new IllegalArgumentException("模板渲染失败：" + e.getMessage());
        }
    }

    @Override
    public void saveTemplate(String template) {
        // 校验失败抛异常，必须在写库之前
        previewRender(template);
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

    private MediaInfo buildSampleMediaInfo() {
        MediaInfo info = new MediaInfo("sample.mkv");
        info.setTitle("示例电影");
        info.setYear("2026");
        info.setSeason("1");
        info.setEpisode("3");
        info.setResolution("1080p");
        info.setSource("WEB-DL");
        info.setVideoCodec("H265");
        info.setAudioCodec("AAC");
        info.setTags(Arrays.asList("HDR"));
        info.setReleaseGroup("EXAMPLE");
        info.setExtension("mkv");
        return info;
    }
}
