package com.osr.openliststrm.backup;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个分区的恢复结果（预览时是「将会」，恢复后是「已经」）。
 *
 * @author Jack
 */
public class SectionResult {

    /** 一个分区最多带回几条提示，再多页面上也读不完 */
    private static final int MAX_WARNINGS = 30;

    private final String key;
    private final String label;
    private int total;
    private int inserted;
    private int updated;
    private int unchanged;
    private int skipped;
    /** 替换模式（重命名分类规则）下被整体替换掉的现有条数 */
    private int replaced;
    /** 恢复时会在后台异步处理（订阅） */
    private boolean async;
    private final List<String> warnings = new ArrayList<>();
    private int omittedWarnings;

    public SectionResult(BackupSection section) {
        this.key = section.key();
        this.label = section.label();
    }

    public void warn(String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        } else {
            omittedWarnings++;
        }
    }

    void total(int total) {
        this.total = total;
    }

    void inserted() {
        inserted++;
    }

    void inserted(int count) {
        inserted += count;
    }

    void updated() {
        updated++;
    }

    void unchanged() {
        unchanged++;
    }

    void unchanged(int count) {
        unchanged += count;
    }

    void skipped() {
        skipped++;
    }

    void skipped(int count) {
        skipped += count;
    }

    void replaced(int replaced) {
        this.replaced = replaced;
    }

    void async(boolean async) {
        this.async = async;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public int getTotal() {
        return total;
    }

    public int getInserted() {
        return inserted;
    }

    public int getUpdated() {
        return updated;
    }

    public int getUnchanged() {
        return unchanged;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getReplaced() {
        return replaced;
    }

    public boolean isAsync() {
        return async;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public int getOmittedWarnings() {
        return omittedWarnings;
    }
}
