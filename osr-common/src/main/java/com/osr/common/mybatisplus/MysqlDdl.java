package com.osr.common.mybatisplus;

import com.baomidou.mybatisplus.extension.ddl.SimpleDdl;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * @Author Jack
 * @Date 2025/7/19 9:57
 * @Version 1.0.0
 */
@Component("mysqlDdl")
public class MysqlDdl extends SimpleDdl {

    /**
     * 执行 SQL 脚本方式
     */
    @Override
    public List<String> getSqlFiles() {
        return Arrays.asList(
                "sql/schema.sql",
                "sql/data.sql",
                "sql/init.sql",
                "sql/20250724.sql",
                "sql/20251010.sql",
                "sql/20260107.sql",
                "sql/20260114.sql",
                "sql/20260203.sql",
                "sql/20260207.sql",
                "sql/20260428-menu-icons.sql",
                "sql/20260510-cleanup-unused-tables.sql",
                "sql/20260511-cleanup-unused-dicts.sql",
                "sql/20260514-fix-password-column-length.sql",
                "sql/20260626-expand-release-group.sql",
                "sql/20260626-delindex.sql",
                "sql/20260711-add-scrape-fields.sql",
                "sql/20260716-add-indexes.sql",
                "sql/20260716-tmdb-cache-incremental-sync.sql",
                "sql/20260718-add-scrape-force-overwrite.sql",
                "sql/20260718-add-openlist-configs.sql",
                "sql/20260719-rename-orphan.sql",
                "sql/20260720-rename-category-rule.sql",
                "sql/20260721-widen-sys-config-value.sql",
                "sql/20260722-fix-rename-config-menu-icon.sql",
                "sql/20260723-fix-menu-icons-mapped-values.sql",
                "sql/20260724-pt-base.sql",
                "sql/20260725-pt-subscription.sql",
                "sql/20260726-pt-filter-and-record-fix.sql",
                "sql/20260727-pt-subscription-menu.sql",
                "sql/20260728-pt-subscription-original-title.sql",
                "sql/20260722-pt-search-supplement.sql",
                "sql/20260729-pt-subscription-imdb-id.sql",
                "sql/20260730-pt-download-record-progress.sql",
                "sql/20260731-pt-download-record-menu.sql",
                "sql/20260732-pt-search-log.sql",
                "sql/20260733-pt-indexer-self-heal.sql",
                "sql/20260734-pt-episode-fail-count.sql",
                "sql/20260735-pt-downloader-strm-task-link.sql",
                "sql/20260736-menu-categories.sql",
                "sql/20260737-fix-menu-group-icon-duplication.sql",
                "sql/20260738-pt-download-record-fail-reason-code.sql",
                "sql/20260739-pt-subscription-download-override.sql",
                "sql/20260740-notify-webhook-config.sql",
                "sql/20260741-pt-stats-menu.sql",
                "sql/20260742-pt-downloader-max-concurrency.sql",
                "sql/20260743-pt-downloader-remove-strm-task-id.sql",
                "sql/20260744-pt-filter-require-chinese-subtitle.sql",
                "sql/20260745-pt-torrent-blacklist.sql",
                "sql/20260746-pt-indexer-poll-cursor.sql",
                "sql/20260747-pt-subscription-auto-search-no-result.sql",
                "sql/20260748-pt-auto-add-rule.sql",
                "sql/20260749-pt-download-record-files-selected.sql",
                "sql/20260750-pt-download-record-episode-end.sql",
                "sql/20260751-pt-subscription-english-title.sql",
                "sql/20260752-flatten-openliststrm-menu.sql",
                "sql/20260753-remove-rename-template-config.sql",
                "sql/20260754-pt-downloader-smart-classify.sql",
                "sql/20260755-notify-type-channel-config.sql",
                "sql/20260756-pt-filter-quality-dimensions.sql",
                "sql/20260757-pt-hit-and-run.sql",
                "sql/20260758-pt-filter-avoid-hit-and-run.sql",
                "sql/20260759-pt-quality-upgrade.sql",
                "sql/20260760-pt-upgrade-menu.sql",
                "sql/20260761-pt-filter-size-per-episode.sql",
                "sql/20260762-wecom-integration.sql",
                "sql/20260763-wecom-auto-provision.sql",
                "sql/20260764-wecom-proxy.sql",
                "sql/20260765-pt-subscription-upgrade-default-off.sql",
                "sql/20260766-pt-search-log-reason-code.sql",
                "sql/20260767-rename-orphan-reverse-scan.sql",
                "sql/20260768-pt-episode-file-confirmed.sql",
                "sql/20260769-copy-recovery-index.sql",
                "sql/20260770-pt-indexer-pubtime-cursor.sql",
                "sql/20260771-pt-downloader-role-and-clean.sql",
                "sql/20260772-copy-transient-dir-filter.sql",
                "sql/20260773-add-create-time-indexes.sql",
                "sql/20260774-login-attempt-limit.sql",
                "sql/20260775-strm-task-override.sql",
                "sql/20260776-pt-episode-air-date.sql",
                "sql/20260777-pt-episode-tmdb-number.sql"
        );
    }
}
