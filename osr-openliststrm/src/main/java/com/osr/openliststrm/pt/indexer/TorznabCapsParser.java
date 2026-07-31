package com.osr.openliststrm.pt.indexer;

import com.osr.common.utils.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 Torznab {@code t=caps} 响应，提取 movie-search/tv-search 是否支持 imdbid/tmdbid 参数。
 *
 * @author Jack
 */
public final class TorznabCapsParser {

    private TorznabCapsParser() {
    }

    /**
     * @param xml t=caps 的响应体，允许为 null/空/非法 XML
     * @return 解析出的能力；响应为空、解析失败、或没有 searching 节点时返回 {@link IndexerCapability#NONE}
     */
    public static IndexerCapability parse(String xml) {
        if (StringUtils.isBlank(xml)) {
            return IndexerCapability.NONE;
        }
        try {
            Document doc = SafeXmlDocuments.parse(xml);
            Element searching = firstChildElement(doc.getDocumentElement(), "searching");
            if (searching == null) {
                return IndexerCapability.NONE;
            }
            Element movieSearch = firstChildElement(searching, "movie-search");
            Element tvSearch = firstChildElement(searching, "tv-search");
            return new IndexerCapability(
                    supportsParam(movieSearch, "imdbid"),
                    supportsParam(movieSearch, "tmdbid"),
                    supportsParam(tvSearch, "imdbid"),
                    supportsParam(tvSearch, "tmdbid"));
        } catch (Exception e) {
            return IndexerCapability.NONE;
        }
    }

    /**
     * @param xml t=caps 的响应体，允许为 null/空/非法 XML
     * @return 解析出的分类树（父分类 + 子分类 subcat）；响应为空、解析失败、或没有 categories 节点时返回空列表
     */
    public static List<CategoryOption> parseCategories(String xml) {
        if (StringUtils.isBlank(xml)) {
            return List.of();
        }
        try {
            Document doc = SafeXmlDocuments.parse(xml);
            Element categoriesEl = firstChildElement(doc.getDocumentElement(), "categories");
            if (categoriesEl == null) {
                return List.of();
            }
            List<CategoryOption> result = new ArrayList<>();
            NodeList children = categoriesEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "category".equals(node.getNodeName())) {
                    result.add(parseCategory((Element) node));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static CategoryOption parseCategory(Element categoryEl) {
        Integer id = Integer.valueOf(categoryEl.getAttribute("id"));
        String name = categoryEl.getAttribute("name");
        List<CategoryOption> children = new ArrayList<>();
        NodeList subNodes = categoryEl.getChildNodes();
        for (int i = 0; i < subNodes.getLength(); i++) {
            Node node = subNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "subcat".equals(node.getNodeName())) {
                Element subEl = (Element) node;
                children.add(new CategoryOption(
                        Integer.valueOf(subEl.getAttribute("id")), subEl.getAttribute("name"), List.of()));
            }
        }
        return new CategoryOption(id, name, children);
    }

    private static boolean supportsParam(Element searchElement, String param) {
        if (searchElement == null) {
            return false;
        }
        if (!"yes".equalsIgnoreCase(searchElement.getAttribute("available"))) {
            return false;
        }
        String supportedParams = searchElement.getAttribute("supportedParams");
        if (StringUtils.isBlank(supportedParams)) {
            return false;
        }
        for (String token : supportedParams.split(",")) {
            if (param.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Element firstChildElement(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }
}
