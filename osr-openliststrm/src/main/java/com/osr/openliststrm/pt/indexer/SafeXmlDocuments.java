package com.osr.openliststrm.pt.indexer;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 索引器响应（Torznab RSS / t=caps）的 XXE 防护 DOM 构建工具。
 * 从 {@link TorznabParser} 抽出，供 {@link TorznabParser} 与 {@link TorznabCapsParser} 共用——
 * 这段是安全相关的固定写法，两处各写一份容易日后改一处漏改一处。
 *
 * @author Jack
 */
public final class SafeXmlDocuments {

    private SafeXmlDocuments() {
    }

    /**
     * @throws IllegalArgumentException xml 非法、含 DTD 声明、或解析失败
     */
    public static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 禁用 DTD，防止 XXE。索引器是外部输入，必须防护。
            // 禁用 DTD 声明即可阻断实体展开，无需再设置 ACCESS_EXTERNAL_* 属性
            // （部分 JAXB 实现不支持这两个属性，设置时会抛异常导致每次解析都失败）
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            // 关闭命名空间感知，使 getElementsByTagName("torznab:attr") 能按字面量匹配
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML响应解析失败：" + e.getMessage(), e);
        }
    }
}
