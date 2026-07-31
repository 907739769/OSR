package com.osr.openliststrm.pt.indexer;

import java.util.List;

/**
 * 索引器 t=caps 探测出的分类树节点：父分类下可能带若干子分类（subcat）。
 *
 * @author Jack
 */
public record CategoryOption(Integer id, String name, List<CategoryOption> children) {
}
