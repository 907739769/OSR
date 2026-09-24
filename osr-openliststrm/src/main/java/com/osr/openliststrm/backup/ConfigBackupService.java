package com.osr.openliststrm.backup;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.openliststrm.backup.EntitySpec.Ref;
import com.osr.openliststrm.backup.EntitySpec.RefKind;
import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtCleanRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.INotifyRoutePlusService;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyTaskPlusService;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmTaskPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtCleanRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameCategoryRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import com.osr.openliststrm.notify.INotifier;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyRouteService;
import com.osr.openliststrm.notify.dto.NotifyMatrix;
import com.osr.openliststrm.pt.filter.FilterConfigAdminService;
import com.osr.openliststrm.pt.filter.FilterConfigCheck;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.upgrade.UpgradeConfigAdminService;
import com.osr.openliststrm.pt.upgrade.UpgradeConfigCheck;
import com.osr.openliststrm.rename.rule.CategoryRuleValidator;
import com.osr.system.domain.SysConfig;
import com.osr.system.service.ISysConfigService;
import com.osr.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 配置备份与恢复。
 * <p>
 * <b>恢复是「按业务键合并」，从不删除</b>：备份里有、本机没有的新增；两边都有的按备份更新；
 * 本机有、备份里没有的原样保留。业务键取名字/路径这类人认得的东西（下载器按名称、STRM 任务按路径），
 * 不取自增 id——两台机器的 id 毫无关系。唯一的例外是重命名分类规则：那是一张有序规则表，
 * 合并没有意义，整表替换（与设置页保存的语义一致）。
 * <p>
 * <b>配置分区在一个事务里</b>：任何一个分区校验失败（比如过滤规则与洗版规则互相矛盾），
 * 全部回滚，不会留下「恢复了一半」的配置。订阅不在事务里，见 {@link SubscriptionRestorer}。
 * <p>
 * <b>敏感字段</b>（密码、API Key、Token）导出时默认置空。恢复时空值表示「备份里没带」，
 * 保留本机现值；新增的行则提示用户去补填。
 *
 * @author Jack
 */
@Slf4j
@Service
public class ConfigBackupService {

    static final String FORMAT = "osr-backup";
    static final int SCHEMA_VERSION = 1;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 参数设置里的敏感项。Bark 地址里带着设备 key、Webhook 地址常带 token，拿到地址就能冒充本机发通知，
     * 所以也算。另有一条按键名后缀的兜底（{@link #isSecretConfig}），新增的 xxx.token 之类不必回来改这里。
     */
    static final Set<String> SECRET_CONFIG_KEYS = Set.of(
            "openlist.server.token", "openlist.api.apikey", "openlist.tg.token",
            "openlist.openai.apikey", "openlist.tmdb.apikey", "openlist.notify.gotify.token",
            "openlist.notify.bark.url", "openlist.notify.webhook.url",
            "openlist.wecom.secret", "openlist.wecom.token", "openlist.wecom.aeskey");

    private static final EntityCodec<OpenlistStrmTaskPlus> STRM_TASK = new EntityCodec<>(OpenlistStrmTaskPlus.class);
    private static final EntityCodec<OpenlistCopyTaskPlus> COPY_TASK = new EntityCodec<>(OpenlistCopyTaskPlus.class);
    private static final EntityCodec<RenameTaskPlus> RENAME_TASK = new EntityCodec<>(RenameTaskPlus.class);
    private static final EntityCodec<RenameCategoryRulePlus> CATEGORY_RULE = new EntityCodec<>(RenameCategoryRulePlus.class);
    private static final EntityCodec<PtDownloaderPlus> DOWNLOADER = new EntityCodec<>(PtDownloaderPlus.class);
    private static final EntityCodec<PtIndexerPlus> INDEXER = new EntityCodec<>(PtIndexerPlus.class);
    private static final EntityCodec<PtMediaServerPlus> MEDIA_SERVER = new EntityCodec<>(PtMediaServerPlus.class);
    private static final EntityCodec<PtFilterConfigPlus> FILTER = new EntityCodec<>(PtFilterConfigPlus.class);
    private static final EntityCodec<PtUpgradeConfigPlus> UPGRADE = new EntityCodec<>(PtUpgradeConfigPlus.class);
    private static final EntityCodec<PtCleanRulePlus> CLEAN_RULE = new EntityCodec<>(PtCleanRulePlus.class);
    private static final EntityCodec<PtTransferRulePlus> TRANSFER_RULE = new EntityCodec<>(PtTransferRulePlus.class);
    private static final EntityCodec<PtAutoAddRulePlus> AUTO_ADD_RULE = new EntityCodec<>(PtAutoAddRulePlus.class);
    private static final EntityCodec<PtTorrentBlacklistPlus> BLACKLIST = new EntityCodec<>(PtTorrentBlacklistPlus.class);
    private static final EntityCodec<WecomUserPlus> WECOM_USER = new EntityCodec<>(WecomUserPlus.class);

    @Autowired private ISysConfigService configService;
    @Autowired private ISysUserService userService;
    @Autowired private INotifyRoutePlusService notifyRoutePlusService;
    @Autowired private NotifyRouteService notifyRouteService;
    @Autowired private List<INotifier> notifiers;
    @Autowired private IOpenlistStrmTaskPlusService strmTaskService;
    @Autowired private IOpenlistCopyTaskPlusService copyTaskService;
    @Autowired private IRenameTaskPlusService renameTaskService;
    @Autowired private IRenameCategoryRulePlusService categoryRuleService;
    @Autowired private IPtDownloaderPlusService downloaderService;
    @Autowired private IPtIndexerPlusService indexerService;
    @Autowired private IPtMediaServerPlusService mediaServerService;
    @Autowired private IPtFilterConfigPlusService filterConfigService;
    @Autowired private FilterConfigAdminService filterAdminService;
    @Autowired private IPtUpgradeConfigPlusService upgradeConfigService;
    @Autowired private UpgradeConfigAdminService upgradeAdminService;
    @Autowired private IPtCleanRulePlusService cleanRuleService;
    @Autowired private IPtTransferRulePlusService transferRuleService;
    @Autowired private IPtAutoAddRulePlusService autoAddRuleService;
    @Autowired private IPtTorrentBlacklistPlusService blacklistService;
    @Autowired private IWecomUserPlusService wecomUserService;
    @Autowired private IPtSubscriptionPlusService subscriptionService;
    @Autowired private SubscriptionRestorer subscriptionRestorer;
    @Autowired private TransactionOperations tx;

    // ---------------- 分区定义 ----------------

    private EntitySpec<?> spec(BackupSection section) {
        return switch (section) {
            case STRM_TASK -> new EntitySpec<>(section, strmTaskService, STRM_TASK, Set.of(), Set.of(), List.of(),
                    j -> j.getString("strmTaskPath"), j -> j.getString("strmTaskPath"));
            case COPY_TASK -> new EntitySpec<>(section, copyTaskService, COPY_TASK, Set.of("lastSyncTime"), Set.of(), List.of(),
                    j -> pair(j.getString("copyTaskSrc"), j.getString("copyTaskDst")),
                    j -> j.getString("copyTaskSrc") + " → " + j.getString("copyTaskDst"));
            case RENAME_TASK -> new EntitySpec<>(section, renameTaskService, RENAME_TASK, Set.of(), Set.of(), List.of(),
                    j -> pair(j.getString("sourceFolder"), j.getString("targetRoot")),
                    j -> j.getString("sourceFolder") + " → " + j.getString("targetRoot"));
            case DOWNLOADER -> new EntitySpec<>(section, downloaderService, DOWNLOADER, Set.of(), Set.of("password"), List.of(),
                    j -> j.getString("name"), j -> j.getString("name"));
            case INDEXER -> new EntitySpec<>(section, indexerService, INDEXER,
                    Set.of("lastPollTime", "lastStatus", "failCount", "disabledAt", "lastSeenGuidHash", "lastSeenPubTime"),
                    Set.of("apiKey"), List.of(), j -> j.getString("name"), j -> j.getString("name"));
            case MEDIA_SERVER -> new EntitySpec<>(section, mediaServerService, MEDIA_SERVER,
                    Set.of("lastCheckTime", "lastCheckOk", "lastCheckError"),
                    Set.of("apiKey"), List.of(), j -> j.getString("name"), j -> j.getString("name"));
            case CLEAN_RULE -> new EntitySpec<>(section, cleanRuleService, CLEAN_RULE, Set.of(), Set.of(),
                    List.of(new Ref("downloaderId", "downloaderName", RefKind.DOWNLOADER, true)),
                    j -> pair(j.getString("downloaderName"), j.getString("name")),
                    j -> j.getString("downloaderName") + " / " + j.getString("name"));
            case TRANSFER_RULE -> new EntitySpec<>(section, transferRuleService, TRANSFER_RULE, Set.of(), Set.of(),
                    List.of(new Ref("sourceDownloaderId", "sourceDownloaderName", RefKind.DOWNLOADER, true),
                            new Ref("targetDownloaderId", "targetDownloaderName", RefKind.DOWNLOADER, true)),
                    j -> j.getString("name"), j -> j.getString("name"));
            case AUTO_ADD_RULE -> new EntitySpec<>(section, autoAddRuleService, AUTO_ADD_RULE, Set.of("lastRunTime"), Set.of(),
                    List.of(new Ref("downloaderId", "downloaderName", RefKind.DOWNLOADER, false)),
                    j -> j.getString("name"), j -> j.getString("name"));
            case TORRENT_BLACKLIST -> new EntitySpec<>(section, blacklistService, BLACKLIST, Set.of(), Set.of(), List.of(),
                    j -> pair(j.getString("type"), j.getString("value")),
                    j -> StringUtils.defaultIfBlank(j.getString("displayValue"), j.getString("value")));
            case WECOM_USER -> new EntitySpec<>(section, wecomUserService, WECOM_USER, Set.of(), Set.of(),
                    List.of(new Ref("sysUserId", "sysUserLoginName", RefKind.USER, true)),
                    j -> j.getString("wecomUserid"), j -> j.getString("wecomUserid"));
            default -> throw new IllegalArgumentException("不是按实体合并的分区：" + section);
        };
    }

    // ---------------- 导出 ----------------

    /**
     * 导出全部分区，返回备份文件的全文（带缩进的 JSON，方便用户自己打开看一眼）。
     *
     * @param includeSecrets 是否以明文带上密码、API Key 等敏感字段
     */
    public String export(boolean includeSecrets) {
        RefNames names = new RefNames();
        JSONObject sections = new JSONObject();
        for (BackupSection section : BackupSection.values()) {
            sections.put(section.key(), exportSection(section, includeSecrets, names));
        }
        JSONObject backup = new JSONObject();
        backup.put("format", FORMAT);
        backup.put("schemaVersion", SCHEMA_VERSION);
        backup.put("exportedAt", LocalDateTime.now().format(TIME_FORMAT));
        backup.put("includeSecrets", includeSecrets);
        backup.put("sections", sections);
        log.info("导出配置备份：含敏感信息={}，{}", includeSecrets, describeCounts(sections));
        return JSON.toJSONString(backup, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls);
    }

    private JSONArray exportSection(BackupSection section, boolean includeSecrets, RefNames names) {
        JSONArray rows = new JSONArray();
        switch (section) {
            case SYS_CONFIG -> {
                for (SysConfig c : configService.selectConfigList(new SysConfig())) {
                    JSONObject row = new JSONObject();
                    row.put("configKey", c.getConfigKey());
                    row.put("configName", c.getConfigName());
                    row.put("configValue", !includeSecrets && isSecretConfig(c.getConfigKey()) ? "" : c.getConfigValue());
                    row.put("configType", c.getConfigType());
                    row.put("remark", c.getRemark());
                    rows.add(row);
                }
            }
            case NOTIFY_ROUTE -> {
                for (NotifyRoutePlus r : notifyRoutePlusService.list()) {
                    JSONObject row = new JSONObject();
                    row.put("notificationType", r.getNotificationType());
                    row.put("channel", r.getChannel());
                    row.put("enabled", r.getEnabled());
                    row.put("recipientScope", r.getRecipientScope());
                    rows.add(row);
                }
            }
            case RENAME_CATEGORY_RULE -> {
                for (RenameCategoryRulePlus rule : categoryRuleService.list(new LambdaQueryWrapper<RenameCategoryRulePlus>()
                        .orderByAsc(RenameCategoryRulePlus::getMediaType).orderByAsc(RenameCategoryRulePlus::getSeq))) {
                    rows.add(categoryRuleForm(rule));
                }
            }
            case FILTER_CONFIG -> rows.add(FILTER.toJson(filterConfigService.getConfig()));
            case UPGRADE_CONFIG -> rows.add(UPGRADE.toJson(upgradeConfigService.getConfig()));
            case SUBSCRIPTION -> {
                for (PtSubscriptionPlus sub : subscriptionService.list(new LambdaQueryWrapper<PtSubscriptionPlus>()
                        .orderByAsc(PtSubscriptionPlus::getId))) {
                    rows.add(subscriptionForm(sub, names));
                }
            }
            default -> exportEntities(spec(section), includeSecrets, names, rows);
        }
        return rows;
    }

    private <T> void exportEntities(EntitySpec<T> spec, boolean includeSecrets, RefNames names, JSONArray rows) {
        for (T entity : spec.service().list()) {
            rows.add(backupForm(spec, entity, includeSecrets, names));
        }
    }

    /** 实体转成备份里的一行：去掉运行时列，引用换成名字，按需抹掉敏感列 */
    private <T> JSONObject backupForm(EntitySpec<T> spec, T entity, boolean includeSecrets, RefNames names) {
        JSONObject json = spec.codec().toJson(entity);
        spec.excluded().forEach(json::remove);
        for (Ref ref : spec.refs()) {
            json.put(ref.nameProperty(), names.name(ref.kind(), json.remove(ref.idProperty())));
        }
        if (!includeSecrets) {
            spec.secrets().forEach(s -> json.put(s, ""));
        }
        return json;
    }

    /** 分类规则的顺序由行序表达，seq 不进备份（恢复时 replaceAll 会按位置重新编号） */
    private static JSONObject categoryRuleForm(RenameCategoryRulePlus rule) {
        JSONObject json = CATEGORY_RULE.toJson(rule);
        json.remove("seq");
        return json;
    }

    /**
     * 订阅只带「用户做过的选择」：订的是哪部哪季、各项开关与覆盖。
     * 集进度、上次搜索时间、总集数这些都由恢复时的 subscribe 在本机重新算出来。
     */
    private static JSONObject subscriptionForm(PtSubscriptionPlus sub, RefNames names) {
        JSONObject row = new JSONObject();
        row.put("tmdbId", sub.getTmdbId());
        row.put("mediaType", sub.getMediaType());
        row.put("season", sub.getSeason());
        row.put("title", sub.getTitle());
        row.put("year", sub.getYear());
        row.put("status", sub.getStatus());
        row.put("upgradeEnabled", sub.getUpgradeEnabled());
        row.put("autoSearch", sub.getAutoSearch());
        row.put("healthIgnored", sub.getHealthIgnored());
        row.put("filterOverride", sub.getFilterOverride());
        row.put("downloadOverride", sub.getDownloadOverride());
        row.put("downloaderName", names.name(RefKind.DOWNLOADER, sub.getDownloaderId()));
        row.put("ownerLoginName", names.name(RefKind.USER, sub.getOwnerUserId()));
        return row;
    }

    // ---------------- 预览与恢复 ----------------

    /** 解析备份文件并试算一遍恢复，不写库。预览里列出文件中出现的全部分区 */
    public BackupPreview preview(String content) {
        JSONObject backup = parse(content);
        JSONObject sections = backup.getJSONObject("sections");
        Set<BackupSection> present = presentSections(sections);
        Context ctx = new Context(true, present, sections);
        List<SectionResult> results = runConfigSections(ctx);
        if (present.contains(BackupSection.SUBSCRIPTION)) {
            SectionResult r = new SectionResult(BackupSection.SUBSCRIPTION);
            prepareSubscriptions(ctx, r);
            results.add(r);
        }
        return new BackupPreview(backup.getString("exportedAt"), backup.getBooleanValue("includeSecrets"), results);
    }

    /**
     * 按所选分区恢复。配置分区同步完成（同一个事务），订阅在后台重建。
     *
     * @param sectionKeys 要恢复的分区键；备份里没有的分区会被忽略
     * @throws IllegalArgumentException 文件不是合法备份、没选分区、或订阅恢复正在进行
     * @throws BackupRestoreException   某个分区校验失败，此时所有配置分区都已回滚
     */
    public RestoreResult restore(String content, Collection<String> sectionKeys) {
        JSONObject backup = parse(content);
        JSONObject sections = backup.getJSONObject("sections");
        Set<BackupSection> selected = EnumSet.noneOf(BackupSection.class);
        for (BackupSection section : presentSections(sections)) {
            if (sectionKeys != null && sectionKeys.contains(section.key())) {
                selected.add(section);
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("没有选择要恢复的内容");
        }
        boolean withSubscriptions = selected.contains(BackupSection.SUBSCRIPTION);
        if (withSubscriptions && subscriptionRestorer.isRunning()) {
            throw new IllegalArgumentException("上一次订阅恢复还在进行中，请等它完成后再试");
        }

        Context ctx = new Context(false, selected, sections);
        List<SectionResult> results;
        try {
            results = tx.execute(status -> runConfigSections(ctx));
        } catch (RuntimeException e) {
            // 参数写库时顺手更新了缓存，事务回滚不会把缓存也滚回去
            configService.resetConfigCache();
            throw e;
        }

        boolean subscriptionsStarted = false;
        if (withSubscriptions) {
            // 放在事务提交之后：订阅按名字引用的下载器此时才对后台线程可见
            SectionResult r = new SectionResult(BackupSection.SUBSCRIPTION);
            List<SubscriptionRestorer.Seed> seeds = prepareSubscriptions(ctx, r);
            results.add(r);
            if (!seeds.isEmpty()) {
                subscriptionRestorer.start(seeds);
                subscriptionsStarted = true;
            }
        }
        log.info("恢复配置备份（导出于 {}）：{}", backup.getString("exportedAt"), describeResults(results));
        return new RestoreResult(results, subscriptionsStarted);
    }

    public SubscriptionRestorer.RestoreStatus subscriptionRestoreStatus() {
        return subscriptionRestorer.status();
    }

    private List<SectionResult> runConfigSections(Context ctx) {
        List<SectionResult> results = new ArrayList<>();
        for (BackupSection section : BackupSection.values()) {
            if (section == BackupSection.SUBSCRIPTION || !ctx.selected.contains(section)) {
                continue;
            }
            JSONArray rows = ctx.rows(section);
            SectionResult r = new SectionResult(section);
            r.total(rows.size());
            try {
                switch (section) {
                    case SYS_CONFIG -> restoreSysConfig(rows, r, ctx);
                    case NOTIFY_ROUTE -> restoreNotifyRoutes(rows, r, ctx);
                    case RENAME_CATEGORY_RULE -> restoreCategoryRules(rows, r, ctx);
                    case FILTER_CONFIG -> restoreFilterConfig(rows, r, ctx);
                    case UPGRADE_CONFIG -> restoreUpgradeConfig(rows, r, ctx);
                    default -> restoreEntities(spec(section), rows, r, ctx);
                }
            } catch (BackupRestoreException e) {
                throw e;
            } catch (RuntimeException e) {
                // 校验器、数据层抛出来的异常补上分区名，否则用户只看到一句「兜底规则必须排在最后一位」不知道是哪块
                throw new BackupRestoreException(section.label() + "：" + e.getMessage(), e);
            }
            if (section == BackupSection.DOWNLOADER) {
                // 后面的分区按名字引用下载器，得看到刚写进去的那几台
                ctx.names.forgetDownloaders();
            }
            results.add(r);
        }
        return results;
    }

    // ---------------- 通用实体分区 ----------------

    private <T> void restoreEntities(EntitySpec<T> spec, JSONArray rows, SectionResult r, Context ctx) {
        EntityCodec<T> codec = spec.codec();
        Map<String, T> existingByKey = new HashMap<>();
        for (T entity : spec.service().list()) {
            existingByKey.putIfAbsent(spec.key().apply(backupForm(spec, entity, true, ctx.names)), entity);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONObject data = new JSONObject(rows.getJSONObject(i));
            String display = spec.display().apply(data);
            String key = spec.key().apply(data);
            if (StringUtils.isBlank(key)) {
                r.skipped();
                r.warn("第 " + (i + 1) + " 条缺少关键字段，已跳过");
                continue;
            }
            if (!seen.add(key)) {
                r.skipped();
                r.warn("「" + display + "」在备份里重复出现，只恢复第一条");
                continue;
            }
            if (!resolveRefs(spec, data, display, r, ctx)) {
                r.skipped();
                continue;
            }
            List<String> missingSecrets = new ArrayList<>();
            for (String secret : spec.secrets()) {
                if (StringUtils.isBlank(data.getString(secret))) {
                    data.remove(secret);
                    missingSecrets.add(secret);
                }
            }
            data.keySet().retainAll(codec.properties());
            spec.excluded().forEach(data::remove);

            T incoming = codec.fromJson(data);
            T existing = existingByKey.get(key);
            if (existing == null) {
                r.inserted();
                if (!missingSecrets.isEmpty()) {
                    r.warn("「" + display + "」的密码/API Key 不在备份里，恢复后请到页面补填");
                }
                if (!ctx.dryRun) {
                    spec.service().save(incoming);
                }
            } else if (!changed(codec, existing, incoming, data.keySet())) {
                r.unchanged();
            } else {
                r.updated();
                if (!ctx.dryRun) {
                    update(spec, existing, incoming, data.keySet());
                }
            }
        }
    }

    /**
     * 把备份里的名字换回本机 id。
     *
     * @return false 表示这一行因必需的引用找不到而跳过（提示已写进 r）
     */
    private <T> boolean resolveRefs(EntitySpec<T> spec, JSONObject data, String display, SectionResult r, Context ctx) {
        for (Ref ref : spec.refs()) {
            String name = data.getString(ref.nameProperty());
            data.remove(ref.nameProperty());
            Object id = StringUtils.isBlank(name) ? null : ctx.resolve(ref.kind(), name);
            if (id == null && ref.required()) {
                r.warn("「" + display + "」引用的" + kindLabel(ref.kind()) + "「" + StringUtils.defaultString(name)
                        + "」在本机不存在，已跳过" + (ref.kind() == RefKind.DOWNLOADER ? "（可以一并勾选恢复下载器）" : ""));
                return false;
            }
            if (id == null && StringUtils.isNotBlank(name)) {
                r.warn("「" + display + "」引用的" + kindLabel(ref.kind()) + "「" + name + "」在本机不存在，已改为默认");
            }
            data.put(ref.idProperty(), id);
        }
        return true;
    }

    /** 只比备份里提供了的列 */
    private static <T> boolean changed(EntityCodec<T> codec, T existing, T incoming, Set<String> properties) {
        for (String property : properties) {
            if (!EntityCodec.sameValue(codec.get(existing, property), codec.get(incoming, property))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把备份里提供了的列写到现有行上。值为 null 的列要显式 set null：
     * MP 的 updateById 会跳过 null 字段，备份里「清空了」的列就会悄悄保留旧值。
     */
    private static <T> void update(EntitySpec<T> spec, T existing, T incoming, Set<String> properties) {
        EntityCodec<T> codec = spec.codec();
        UpdateWrapper<T> wrapper = new UpdateWrapper<T>().eq(codec.idColumn(), codec.id(existing));
        for (String property : properties) {
            Object value = codec.get(incoming, property);
            codec.set(existing, property, value);
            if (value == null) {
                wrapper.set(codec.column(property), null);
            }
        }
        spec.service().update(existing, wrapper);
    }

    // ---------------- 特殊分区 ----------------

    private void restoreSysConfig(JSONArray rows, SectionResult r, Context ctx) {
        Map<String, SysConfig> existing = configService.selectConfigList(new SysConfig()).stream()
                .collect(Collectors.toMap(SysConfig::getConfigKey, c -> c, (a, b) -> a));
        int keptSecrets = 0;
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String key = row.getString("configKey");
            if (StringUtils.isBlank(key)) {
                r.skipped();
                continue;
            }
            String value = StringUtils.defaultString(row.getString("configValue"));
            SysConfig current = existing.get(key);
            if (isSecretConfig(key) && value.isEmpty() && current != null) {
                keptSecrets++;
                r.unchanged();
                continue;
            }
            if (current == null) {
                r.inserted();
                if (!ctx.dryRun) {
                    SysConfig config = new SysConfig();
                    config.setConfigKey(key);
                    config.setConfigName(StringUtils.defaultIfBlank(row.getString("configName"), key));
                    config.setConfigValue(value);
                    config.setConfigType(StringUtils.defaultIfBlank(row.getString("configType"), "N"));
                    config.setRemark(row.getString("remark"));
                    configService.insertConfig(config);
                }
            } else if (Objects.equals(StringUtils.defaultString(current.getConfigValue()), value)) {
                r.unchanged();
            } else {
                r.updated();
                if (!ctx.dryRun) {
                    current.setConfigValue(value);
                    configService.updateConfig(current);
                }
            }
        }
        if (keptSecrets > 0) {
            r.warn(keptSecrets + " 个敏感参数（Token、API Key 等）不在备份里，保留了本机当前的值");
        }
    }

    private void restoreNotifyRoutes(JSONArray rows, SectionResult r, Context ctx) {
        Set<String> channels = notifiers.stream().map(INotifier::channelKey).collect(Collectors.toSet());
        Set<String> types = java.util.Arrays.stream(NotificationType.values()).map(Enum::name).collect(Collectors.toSet());
        Map<String, NotifyRoutePlus> existing = notifyRoutePlusService.list().stream()
                .collect(Collectors.toMap(n -> pair(n.getNotificationType(), n.getChannel()), n -> n, (a, b) -> a));
        List<NotifyMatrix.RouteItem> items = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String type = row.getString("notificationType");
            String channel = row.getString("channel");
            if (!types.contains(type) || !channels.contains(channel)) {
                r.skipped();
                r.warn("不认识的通知类型或渠道「" + type + " / " + channel + "」（备份可能来自其他版本），已跳过");
                continue;
            }
            String enabled = "1".equals(row.getString("enabled")) ? "1" : "0";
            String scope = row.getString("recipientScope");
            NotifyRoutePlus current = existing.get(pair(type, channel));
            if (current == null) {
                r.inserted();
            } else if (enabled.equals(current.getEnabled()) && Objects.equals(scope, current.getRecipientScope())) {
                r.unchanged();
            } else {
                r.updated();
            }
            items.add(new NotifyMatrix.RouteItem(type, channel, "1".equals(enabled), scope));
        }
        if (!ctx.dryRun && !items.isEmpty()) {
            // 走设置页同一个保存入口：它会校验并让路由缓存失效
            notifyRouteService.saveAll(items);
        }
    }

    private void restoreCategoryRules(JSONArray rows, SectionResult r, Context ctx) {
        Map<String, List<JSONObject>> byType = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            byType.computeIfAbsent(StringUtils.defaultString(row.getString("mediaType")), k -> new ArrayList<>()).add(row);
        }
        int replaced = 0;
        for (Map.Entry<String, List<JSONObject>> entry : byType.entrySet()) {
            String mediaType = entry.getKey();
            List<JSONObject> backupRows = entry.getValue();
            if (mediaType.isEmpty()) {
                r.skipped(backupRows.size());
                r.warn(backupRows.size() + " 条规则缺少媒体类型，已跳过");
                continue;
            }
            List<JSONObject> currentRows = categoryRuleService.list(new LambdaQueryWrapper<RenameCategoryRulePlus>()
                            .eq(RenameCategoryRulePlus::getMediaType, mediaType)
                            .orderByAsc(RenameCategoryRulePlus::getSeq))
                    .stream().map(ConfigBackupService::categoryRuleForm).toList();
            List<RenameCategoryRulePlus> rules = backupRows.stream().map(CATEGORY_RULE::fromJson).toList();
            List<JSONObject> normalized = rules.stream().map(ConfigBackupService::categoryRuleForm).toList();
            if (normalized.equals(currentRows)) {
                r.unchanged(rules.size());
                continue;
            }
            replaced += currentRows.size();
            r.inserted(rules.size());
            if (ctx.dryRun) {
                try {
                    CategoryRuleValidator.validate(new ArrayList<>(rules));
                } catch (IllegalArgumentException e) {
                    r.warn(mediaType + " 规则恢复时会被拒绝：" + e.getMessage());
                }
            } else {
                categoryRuleService.replaceAll(mediaType, new ArrayList<>(rules));
            }
        }
        r.replaced(replaced);
    }

    /**
     * 过滤规则与洗版规则互相校验：洗版规则引用了过滤规则里的优先级。两者一起恢复时，
     * 过滤规则不能再拿本机<b>旧的</b>洗版规则去校验（那样备份里自洽的一对配置会被拒），
     * 只做自身校验，交叉校验留给紧接着的洗版规则分区。
     */
    private void restoreFilterConfig(JSONArray rows, SectionResult r, Context ctx) {
        if (rows.isEmpty()) {
            return;
        }
        JSONObject data = rows.getJSONObject(0);
        PtFilterConfigPlus incoming = FILTER.fromJson(data);
        incoming.setId(PtFilterConfigPlus.SINGLETON_ID);
        PtFilterConfigPlus current = filterConfigService.getConfig();
        if (!changed(FILTER, current, incoming, keysOf(data, FILTER))) {
            r.unchanged();
            return;
        }
        r.updated();
        List<String> errors = FilterConfigCheck.errors(incoming);
        if (ctx.dryRun) {
            errors.forEach(e -> r.warn("恢复时会被拒绝：" + e));
            return;
        }
        if (!ctx.selected.contains(BackupSection.UPGRADE_CONFIG)) {
            errors = filterAdminService.save(incoming);
        } else if (errors.isEmpty()) {
            filterConfigService.saveOrUpdate(incoming);
            if (UpgradeConfigAdminService.prioritiesChanged(current, incoming)) {
                upgradeAdminService.resetEvaluations();
            }
        }
        if (!errors.isEmpty()) {
            throw new BackupRestoreException(BackupSection.FILTER_CONFIG.label() + "：" + String.join("；", errors));
        }
    }

    private void restoreUpgradeConfig(JSONArray rows, SectionResult r, Context ctx) {
        if (rows.isEmpty()) {
            return;
        }
        JSONObject data = rows.getJSONObject(0);
        PtUpgradeConfigPlus incoming = UPGRADE.fromJson(data);
        incoming.setId(PtUpgradeConfigPlus.SINGLETON_ID);
        PtUpgradeConfigPlus current = upgradeConfigService.getConfig();
        if (!changed(UPGRADE, current, incoming, keysOf(data, UPGRADE))) {
            r.unchanged();
            return;
        }
        r.updated();
        if (ctx.dryRun) {
            UpgradeConfigCheck.errors(incoming).forEach(e -> r.warn("恢复时会被拒绝：" + e));
            return;
        }
        List<String> errors = upgradeAdminService.save(incoming);
        if (!errors.isEmpty()) {
            throw new BackupRestoreException(BackupSection.UPGRADE_CONFIG.label() + "：" + String.join("；", errors));
        }
    }

    /**
     * 订阅：已存在的（同一 TMDb ID + 类型 + 季）一律跳过、不改动——它们带着本机的下载进度，
     * 用备份里的开关覆盖上去反而可能打断正在进行的事。
     */
    private List<SubscriptionRestorer.Seed> prepareSubscriptions(Context ctx, SectionResult r) {
        JSONArray rows = ctx.rows(BackupSection.SUBSCRIPTION);
        r.total(rows.size());
        r.async(true);
        Set<String> existing = subscriptionService.list().stream()
                .map(s -> subscriptionKey(s.getTmdbId(), s.getMediaType(), s.getSeason()))
                .collect(Collectors.toSet());
        List<SubscriptionRestorer.Seed> seeds = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String tmdbId = row.getString("tmdbId");
            String mediaType = StringUtils.upperCase(row.getString("mediaType"), Locale.ROOT);
            if (StringUtils.isAnyBlank(tmdbId, mediaType)) {
                r.skipped();
                r.warn("第 " + (i + 1) + " 条订阅缺少 TMDb ID 或类型，已跳过");
                continue;
            }
            boolean movie = SubscriptionService.TYPE_MOVIE.equals(mediaType);
            Integer season = movie ? 0 : row.getInteger("season");
            String display = subscriptionDisplay(row, movie);
            if (!movie && season == null) {
                r.skipped();
                r.warn(display + " 缺少季号，已跳过");
                continue;
            }
            if (!existing.add(subscriptionKey(tmdbId, mediaType, season))) {
                r.unchanged();
                continue;
            }
            Integer downloaderId = null;
            String downloaderName = row.getString("downloaderName");
            if (StringUtils.isNotBlank(downloaderName)) {
                downloaderId = (Integer) ctx.resolve(RefKind.DOWNLOADER, downloaderName);
                if (downloaderId == null) {
                    r.warn(display + " 指定的下载器「" + downloaderName + "」在本机不存在，改用默认下载器");
                }
            }
            Long ownerUserId = null;
            String ownerLogin = row.getString("ownerLoginName");
            if (StringUtils.isNotBlank(ownerLogin)) {
                ownerUserId = (Long) ctx.resolve(RefKind.USER, ownerLogin);
                if (ownerUserId == null) {
                    r.warn(display + " 的归属用户「" + ownerLogin + "」在本机不存在，恢复为无归属（所有人可见）");
                }
            }
            r.inserted();
            seeds.add(new SubscriptionRestorer.Seed(tmdbId, mediaType, season, display, row.getString("status"),
                    row.getString("upgradeEnabled"), row.getString("autoSearch"), row.getString("healthIgnored"),
                    row.getString("filterOverride"), row.getString("downloadOverride"), downloaderId, ownerUserId));
        }
        return seeds;
    }

    // ---------------- 工具 ----------------

    /** 解析并校验备份文件的外壳 */
    static JSONObject parse(String content) {
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("备份文件是空的");
        }
        JSONObject backup;
        try {
            backup = JSON.parseObject(content);
        } catch (JSONException e) {
            throw new IllegalArgumentException("备份文件不是合法的 JSON：" + e.getMessage());
        }
        if (backup == null || !FORMAT.equals(backup.getString("format"))) {
            throw new IllegalArgumentException("这不是 OSR 的配置备份文件");
        }
        Integer version = backup.getInteger("schemaVersion");
        if (version == null || version > SCHEMA_VERSION) {
            throw new IllegalArgumentException("备份文件来自更新版本的 OSR（格式版本 " + version + "），请先升级本机");
        }
        if (backup.getJSONObject("sections") == null) {
            throw new IllegalArgumentException("备份文件里没有任何内容");
        }
        return backup;
    }

    private static Set<BackupSection> presentSections(JSONObject sections) {
        Set<BackupSection> present = EnumSet.noneOf(BackupSection.class);
        for (String key : sections.keySet()) {
            BackupSection.ofKey(key).ifPresent(present::add);
        }
        return present;
    }

    static boolean isSecretConfig(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return SECRET_CONFIG_KEYS.contains(lower)
                || lower.endsWith("password") || lower.endsWith("secret") || lower.endsWith("token")
                || lower.endsWith("apikey") || lower.endsWith("aeskey");
    }

    private static <T> Set<String> keysOf(JSONObject data, EntityCodec<T> codec) {
        Set<String> keys = new HashSet<>(data.keySet());
        keys.retainAll(codec.properties());
        return keys;
    }

    /** 两段拼成一个业务键；任一段为空返回 null（视为缺少关键字段） */
    private static String pair(String a, String b) {
        return StringUtils.isAnyBlank(a, b) ? null : a + "\u0000" + b;
    }

    private static String subscriptionKey(String tmdbId, String mediaType, Integer season) {
        return tmdbId + "|" + StringUtils.upperCase(mediaType, Locale.ROOT) + "|" + season;
    }

    private static String subscriptionDisplay(JSONObject row, boolean movie) {
        StringBuilder sb = new StringBuilder("《").append(StringUtils.defaultIfBlank(row.getString("title"), row.getString("tmdbId"))).append('》');
        if (StringUtils.isNotBlank(row.getString("year"))) {
            sb.append('(').append(row.getString("year")).append(')');
        }
        if (!movie && row.getInteger("season") != null) {
            sb.append(" 第").append(row.getInteger("season")).append("季");
        }
        return sb.toString();
    }

    private static String kindLabel(RefKind kind) {
        return kind == RefKind.DOWNLOADER ? "下载器" : "用户";
    }

    private static String describeCounts(JSONObject sections) {
        return sections.entrySet().stream()
                .map(e -> BackupSection.ofKey(e.getKey()).map(BackupSection::label).orElse(e.getKey())
                        + " " + ((JSONArray) e.getValue()).size())
                .collect(Collectors.joining("，"));
    }

    private static String describeResults(List<SectionResult> results) {
        return results.stream()
                .map(r -> r.getLabel() + (r.isAsync() ? " 待后台新建 " : " 新增 ") + r.getInserted()
                        + (r.isAsync() ? "" : " 更新 " + r.getUpdated()) + " 跳过 " + r.getSkipped())
                .collect(Collectors.joining("；"));
    }

    /** 一次预览/恢复的上下文 */
    private final class Context {
        private final boolean dryRun;
        private final Set<BackupSection> selected;
        private final JSONObject sections;
        private final RefNames names = new RefNames();
        /** 预览时：本次会一并恢复的下载器名字，视为「恢复后就有了」 */
        private final Set<String> plannedDownloaders = new HashSet<>();

        Context(boolean dryRun, Set<BackupSection> selected, JSONObject sections) {
            this.dryRun = dryRun;
            this.selected = selected;
            this.sections = sections;
            if (dryRun && selected.contains(BackupSection.DOWNLOADER)) {
                JSONArray rows = rows(BackupSection.DOWNLOADER);
                for (int i = 0; i < rows.size(); i++) {
                    plannedDownloaders.add(rows.getJSONObject(i).getString("name"));
                }
            }
        }

        JSONArray rows(BackupSection section) {
            JSONArray rows = sections.getJSONArray(section.key());
            return rows == null ? new JSONArray() : rows;
        }

        /** 名字换本机 id；预览时「会一并恢复」的下载器返回占位 id -1 */
        Object resolve(RefKind kind, String name) {
            Object id = names.id(kind, name);
            if (id == null && dryRun && kind == RefKind.DOWNLOADER && plannedDownloaders.contains(name)) {
                return -1;
            }
            return id;
        }
    }

    /** id 与名字的双向查找，按需加载、在一次导出/恢复里复用 */
    private final class RefNames {
        private Map<Integer, String> downloaderNames;
        private final Map<Long, String> userNames = new HashMap<>();
        private final Map<String, Long> userIds = new HashMap<>();

        String name(RefKind kind, Object id) {
            if (id == null) {
                return null;
            }
            if (kind == RefKind.DOWNLOADER) {
                return downloaders().get((Integer) id);
            }
            return userNames.computeIfAbsent((Long) id, uid -> {
                SysUser user = userService.selectUserById(uid);
                return user == null ? null : user.getLoginName();
            });
        }

        Object id(RefKind kind, String name) {
            if (kind == RefKind.DOWNLOADER) {
                return downloaders().entrySet().stream()
                        .filter(e -> name.equals(e.getValue()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
            }
            return userIds.computeIfAbsent(name, n -> {
                SysUser user = userService.selectUserByLoginName(n);
                return user == null ? null : user.getUserId();
            });
        }

        void forgetDownloaders() {
            downloaderNames = null;
        }

        private Map<Integer, String> downloaders() {
            if (downloaderNames == null) {
                downloaderNames = new LinkedHashMap<>();
                for (PtDownloaderPlus d : downloaderService.list(new LambdaQueryWrapper<PtDownloaderPlus>()
                        .orderByAsc(PtDownloaderPlus::getId))) {
                    downloaderNames.put(d.getId(), d.getName());
                }
            }
            return downloaderNames;
        }
    }

    /**
     * 预览结果。
     *
     * @param exportedAt     备份的导出时间
     * @param includeSecrets 备份里是否带了敏感字段
     * @param sections       各分区试算结果
     */
    public record BackupPreview(String exportedAt, boolean includeSecrets, List<SectionResult> sections) {
    }

    /**
     * 恢复结果。
     *
     * @param sections             各分区结果（订阅那一项是「将在后台新建」的条数）
     * @param subscriptionsRestoring 订阅是否已在后台开始重建，页面据此轮询进度
     */
    public record RestoreResult(List<SectionResult> sections, boolean subscriptionsRestoring) {
    }
}
