package com.osr.openliststrm.pt.autoadd.source;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.pt.indexer.SafeXmlDocuments;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSSHub 豆瓣榜单响应解析器。纯函数，无 IO，无 Spring 依赖。
 * <p>
 * 解析出的 {@link PopularItem} <b>只有标题、年份、豆瓣 subject id</b>——RSSHub 的豆瓣路由
 * 不返回 IMDb ID 与 TMDb ID，甚至多数路由连年份都不给，而整条建订阅链路都是围绕 tmdbId 建的。
 * 补全那一步交给 {@code PopularItemResolver}，本类不碰。
 * </p>
 *
 * @author Jack
 */
@Slf4j
public final class DoubanRssParser {

    /**
     * 尾部评分，<b>必须带小数点</b>。豆瓣评分一律一位小数（{@code 8.5}、{@code 7.0}），
     * 而放开成「尾部孤立数字」会把《速度与激情 9》的季号/序号当成评分剥掉——
     * 那类标题在榜单里相当常见，剥错了后面标题全等判定必然落空，且不会有任何报错。
     */
    private static final Pattern TRAILING_RATING = Pattern.compile("\\s+\\d{1,2}\\.\\d\\s*$");

    /** 尾部年份括号（半角/全角都收），命中即剥掉并把年份带出来 */
    private static final Pattern TRAILING_YEAR = Pattern.compile("[(（]\\s*((?:19|20)\\d{2})\\s*[)）]\\s*$");

    /** 豆瓣条目链接里的 subject id */
    private static final Pattern SUBJECT_ID = Pattern.compile("/subject/(\\d+)");

    private DoubanRssParser() {
    }

    /**
     * 解析 RSS 2.0 或 Atom 响应。
     *
     * @param xml 响应体，允许为 null 或空
     * @return 候选条目，顺序与响应一致；无有效条目时返回空列表
     * @throws IllegalArgumentException XML 格式非法，或包含 DTD 声明
     */
    public static List<PopularItem> parse(String xml) {
        List<PopularItem> result = new ArrayList<>();
        if (StringUtils.isBlank(xml)) {
            return result;
        }
        Document doc = SafeXmlDocuments.parse(xml);
        Element root = doc.getDocumentElement();
        if (root == null) {
            return result;
        }
        // RSSHub 同一个路由带不带 .atom 后缀给的是两种格式，用户很容易把带后缀的地址粘过来。
        // 只支持 RSS 的话表现是「一条都拉不到」而地址看着完全正常，多这十几行省掉一次排查。
        Element channel = firstChildElement(root, "channel");
        if (channel != null) {
            collect(channel, "item", "link", result);
        } else if ("feed".equals(localName(root))) {
            collect(root, "entry", "link", result);
        } else {
            log.warn("RSS 响应既不是 RSS 2.0 的 channel 也不是 Atom 的 feed，根元素={}", localName(root));
        }
        return result;
    }

    private static void collect(Element parent, String itemTag, String linkTag, List<PopularItem> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE || !itemTag.equals(localName(node))) {
                continue;
            }
            PopularItem item = parseItem((Element) node, linkTag);
            if (item != null) {
                result.add(item);
            }
        }
    }

    private static PopularItem parseItem(Element item, String linkTag) {
        String rawTitle = childText(item, "title");
        if (StringUtils.isBlank(rawTitle)) {
            log.debug("RSS 条目缺少 title，已丢弃");
            return null;
        }
        PopularItem result = new PopularItem();
        String link = resolveLink(item, linkTag);
        result.setSourceUrl(link);
        result.setDoubanId(extractSubjectId(link));

        String title = rawTitle.trim();
        title = stripTrailing(title, TRAILING_RATING);
        Matcher year = TRAILING_YEAR.matcher(title);
        if (year.find()) {
            String stripped = title.substring(0, year.start()).trim();
            // 剥空则不剥：整个标题就是一个年份（豆列里有《1917》这类片名）时，
            // 剥掉之后什么都不剩，拿空串去搜 TMDb 只会白打一次请求
            if (!stripped.isEmpty()) {
                result.setYear(year.group(1));
                title = stripped;
            }
        }
        result.setTitle(title);
        return StringUtils.isBlank(result.getTitle()) ? null : result;
    }

    /** Atom 的链接在 {@code <link href="…"/>} 属性上，RSS 在文本节点里 */
    private static String resolveLink(Element item, String linkTag) {
        Element link = firstChildElement(item, linkTag);
        if (link == null) {
            return null;
        }
        String href = StringUtils.trimToNull(link.getAttribute("href"));
        return href != null ? href : StringUtils.trimToNull(link.getTextContent());
    }

    private static String extractSubjectId(String link) {
        if (StringUtils.isBlank(link)) {
            return null;
        }
        Matcher m = SUBJECT_ID.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    /** 命中即剥掉尾部片段；剥空则原样返回（剥到什么都不剩说明这个模式在这条标题上判错了） */
    private static String stripTrailing(String title, Pattern pattern) {
        Matcher m = pattern.matcher(title);
        if (!m.find()) {
            return title;
        }
        String stripped = title.substring(0, m.start()).trim();
        return stripped.isEmpty() ? title : stripped;
    }

    private static Element firstChildElement(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(localName(node))) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element el = firstChildElement(parent, tag);
        return el == null ? null : StringUtils.trimToNull(el.getTextContent());
    }

    /** Atom 文档常带命名空间前缀，getNodeName 会带上 {@code atom:}，按 localName 比对更稳 */
    private static String localName(Node node) {
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }
}
