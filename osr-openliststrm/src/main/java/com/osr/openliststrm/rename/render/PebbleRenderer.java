package com.osr.openliststrm.rename.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osr.openliststrm.rename.model.MediaInfo;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.StringLoader;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

/**
 * @Author Jack
 * @Date 2025/8/12 16:54
 * @Version 1.0.0
 */
public class PebbleRenderer {
    private static final ObjectMapper CONTEXT_MAPPER = new ObjectMapper();

    private final PebbleEngine engine;

    public PebbleRenderer() {
        this.engine = new PebbleEngine.Builder().loader(new StringLoader()).cacheActive(true).build();
    }

    /**
     * 模板里能用的变量就是这张表的 key。「重命名规则设置」页的变量清单也从这里取，
     * 而不是另抄一份字段名：MediaInfo 加了字段，页面上自动出现，不会漂。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> contextOf(MediaInfo info) {
        return CONTEXT_MAPPER.convertValue(info, Map.class);
    }

    public String render(MediaInfo info, String templateString) {
        try {
            PebbleTemplate tmpl = engine.getTemplate(templateString);
            Map<String, Object> ctx = contextOf(info);
            Writer w = new StringWriter();
            tmpl.evaluate(w, ctx);
            return w.toString().replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}