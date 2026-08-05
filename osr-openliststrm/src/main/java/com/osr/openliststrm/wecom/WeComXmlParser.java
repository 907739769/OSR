package com.osr.openliststrm.wecom;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/**
 * 企微回调 XML 报文解析。
 * <p>
 * 解析器<b>必须</b>禁用 DTD 与外部实体：报文来自公网，虽然有签名校验兜在前面，
 * 但解析发生在业务层，一旦哪天有人把校验顺序调换，开着 DOCTYPE 的解析器就是一个
 * 直读服务器文件的 XXE 洞。这里一次性关死，不依赖调用顺序的正确性。
 *
 * @author Jack
 */
public final class WeComXmlParser {

    private WeComXmlParser() {
    }

    /**
     * 从外层密文信封里取出 {@code <Encrypt>} 节点。
     *
     * @throws IllegalArgumentException 报文格式非法或缺少 Encrypt 节点
     */
    public static String extractEncrypt(String xml) {
        String encrypt = readText(parse(xml), "Encrypt");
        if (encrypt == null || encrypt.isBlank()) {
            throw new IllegalArgumentException("企微回调报文缺少 Encrypt 节点");
        }
        return encrypt;
    }

    /**
     * 解析解密后的明文消息体。
     *
     * @throws IllegalArgumentException 报文格式非法
     */
    public static WeComInboundMessage parseMessage(String xml) {
        Document document = parse(xml);
        return new WeComInboundMessage(
                readText(document, "FromUserName"),
                readText(document, "MsgType"),
                readText(document, "Content"),
                readText(document, "Event"),
                readText(document, "EventKey"));
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("企微回调报文解析失败：" + e.getMessage(), e);
        }
    }

    /** 取第一个同名节点的文本内容，不存在返回 null */
    private static String readText(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        Element element = (Element) nodes.item(0);
        String text = element.getTextContent();
        return text == null ? null : text.trim();
    }
}
