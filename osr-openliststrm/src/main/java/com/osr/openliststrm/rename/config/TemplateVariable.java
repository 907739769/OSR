package com.osr.openliststrm.rename.config;

/**
 * 文件名模板里的一个可用变量，供「重命名规则设置」页的变量清单展示。
 *
 * @param name   变量名，模板里写 {@code {{ name }}}
 * @param label  中文说明；新加的字段还没登记说明时为 null，页面只显示变量名
 * @param sample 剧集样例里的取值，列表按「, 」拼接，空值为空串
 * @param common 是否常用；页面先展示常用的，其余收在「更多」里
 */
public record TemplateVariable(String name, String label, String sample, boolean common) {
}
