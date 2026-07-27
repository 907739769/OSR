package com.ruoyi.openliststrm.pt.indexer;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.pt.model.TorrentInfo;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * Torznab / Newznab 响应解析器。纯函数，无 IO，无 Spring 依赖。
 * <p>
 * 不同索引器实现差异较大，本解析器对以下情况全部容错：
 * 命名空间前缀 torznab 与 newznab 混用、体积仅存在于 enclosure、
 * 下载地址仅存在于 enclosure、结构化属性整体缺失、属性值非数字。
 * </p>
 *
 * @author Jack
 */
@Slf4j
public final class TorznabParser {

    private TorznabParser() {
    }

    /**
     * 解析 Torznab XML 响应。
     *
     * @param xml 响应体，允许为 null 或空
     * @return 解析出的种子列表，顺序与响应一致；无有效条目时返回空列表
     * @throws IllegalArgumentException XML 格式非法，或包含 DTD 声明
     */
    public static List<TorrentInfo> parse(String xml) {
        List<TorrentInfo> result = new ArrayList<>();
        if (StringUtils.isBlank(xml)) {
            return result;
        }
        Document doc = buildDocument(xml);
        Element channel = firstChildElement(doc.getDocumentElement(), "channel");
        if (channel == null) {
            return result;
        }
        NodeList children = channel.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "item".equals(node.getNodeName())) {
                TorrentInfo info = parseItem((Element) node);
                if (info != null) {
                    result.add(info);
                }
            }
        }
        return result;
    }

    private static Document buildDocument(String xml) {
        return SafeXmlDocuments.parse(xml);
    }

    private static TorrentInfo parseItem(Element item) {
        String title = childText(item, "title");
        if (StringUtils.isBlank(title)) {
            log.debug("Torznab条目缺少title，已丢弃");
            return null;
        }

        Element enclosure = firstChildElement(item, "enclosure");

        String downloadUrl = childText(item, "link");
        if (StringUtils.isBlank(downloadUrl) && enclosure != null) {
            downloadUrl = StringUtils.trimToNull(enclosure.getAttribute("url"));
        }
        if (StringUtils.isBlank(downloadUrl)) {
            log.debug("Torznab条目缺少下载地址，已丢弃：{}", title);
            return null;
        }

        // guid 是 RSS 规范中的可选元素，索引器缺失时回退用下载地址，
        // 宁可去重键偶尔失效（同种子当成两条），也不能因缺 guid 丢弃整条数据。
        String guid = childText(item, "guid");
        if (StringUtils.isBlank(guid)) {
            log.debug("Torznab条目缺少guid，已降级使用下载地址作为去重标识：{}", title);
            guid = downloadUrl;
        }

        TorrentInfo info = new TorrentInfo();
        info.setTitle(title);
        info.setGuid(guid);
        info.setDownloadUrl(downloadUrl);
        info.setPubDate(childText(item, "pubDate"));

        long size = parseLong(childText(item, "size"), 0L);
        if (size == 0L && enclosure != null) {
            size = parseLong(enclosure.getAttribute("length"), 0L);
        }
        if (size == 0L) {
            size = parseLong(attrValue(item, "size"), 0L);
        }
        info.setSize(size);

        info.setDescription(StringUtils.trimToNull(childText(item, "description")));

        info.setSeeders((int) parseLong(attrValue(item, "seeders"), 0L));
        info.setPeers((int) parseLong(attrValue(item, "peers"), 0L));
        info.setInfoHash(StringUtils.trimToNull(attrValue(item, "infohash")));
        // 未提供促销信息时按正常计量处理，绝不能默认成免费
        info.setDownloadVolumeFactor(parseDouble(attrValue(item, "downloadvolumefactor"), 1.0));

        return info;
    }

    /**
     * 读取 torznab:attr / newznab:attr / attr 中 name 匹配的 value，大小写不敏感。
     */
    private static String attrValue(Element item, String name) {
        NodeList children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = node.getNodeName();
            if (!"attr".equals(tag) && !tag.endsWith(":attr")) {
                continue;
            }
            Element el = (Element) node;
            if (name.equalsIgnoreCase(el.getAttribute("name"))) {
                return el.getAttribute("value");
            }
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element el = firstChildElement(parent, tag);
        return el == null ? null : StringUtils.trimToNull(el.getTextContent());
    }

    private static Element firstChildElement(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static long parseLong(String value, long fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
