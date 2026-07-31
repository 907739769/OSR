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
    private final PebbleEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public PebbleRenderer() {
        this.engine = new PebbleEngine.Builder().loader(new StringLoader()).cacheActive(true).build();
    }

    public String render(MediaInfo info, String templateString) {
        try {
            PebbleTemplate tmpl = engine.getTemplate(templateString);
            Map<String, Object> ctx = mapper.convertValue(info, Map.class);
            Writer w = new StringWriter();
            tmpl.evaluate(w, ctx);
            return w.toString().replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}