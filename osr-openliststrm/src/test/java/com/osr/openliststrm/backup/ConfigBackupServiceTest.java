package com.osr.openliststrm.backup;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.mybatisplus.domain.PtCleanRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtCleanRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import com.osr.system.domain.SysConfig;
import com.osr.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置备份里判错了不报错、只会悄悄做错事的几条：密码外泄或被空值冲掉、
 * 下载器按 id 而不是按名字对应、找不到引用时硬塞进去、已有订阅被覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigBackupServiceTest {

    @Mock private ISysConfigService configService;
    @Mock private IPtDownloaderPlusService downloaderService;
    @Mock private IPtCleanRulePlusService cleanRuleService;
    @Mock private IPtFilterConfigPlusService filterConfigService;
    @Mock private IPtUpgradeConfigPlusService upgradeConfigService;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private SubscriptionRestorer subscriptionRestorer;
    // 以下只为让导出能遍历全部分区，默认返回空表
    @Mock private com.osr.system.service.ISysUserService userService;
    @Mock private com.osr.openliststrm.mybatisplus.service.INotifyRoutePlusService notifyRoutePlusService;
    @Mock private com.osr.openliststrm.notify.NotifyRouteService notifyRouteService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IOpenlistStrmTaskPlusService strmTaskService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IOpenlistCopyTaskPlusService copyTaskService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService renameTaskService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IRenameCategoryRulePlusService categoryRuleService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService indexerService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService mediaServerService;
    @Mock private com.osr.openliststrm.pt.filter.FilterConfigAdminService filterAdminService;
    @Mock private com.osr.openliststrm.pt.upgrade.UpgradeConfigAdminService upgradeAdminService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IPtTransferRulePlusService transferRuleService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IPtAutoAddRulePlusService autoAddRuleService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService blacklistService;
    @Mock private com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService wecomUserService;

    @InjectMocks private ConfigBackupService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "tx", TransactionOperations.withoutTransaction());
        when(filterConfigService.getConfig()).thenReturn(new PtFilterConfigPlus());
        when(upgradeConfigService.getConfig()).thenReturn(new PtUpgradeConfigPlus());
    }

    @SuppressWarnings("unchecked")
    private static <T> com.baomidou.mybatisplus.core.conditions.Wrapper<T> anyWrapper() {
        return any(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
    }

    private static PtDownloaderPlus downloader(int id, String name, String password) {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(id);
        d.setName(name);
        d.setType("QBITTORRENT");
        d.setHost("10.0.0.2");
        d.setPassword(password);
        return d;
    }

    private static SysConfig config(String key, String value) {
        SysConfig c = new SysConfig();
        c.setConfigId(1L);
        c.setConfigKey(key);
        c.setConfigValue(value);
        return c;
    }

    private static String backup(JSONObject sections) {
        JSONObject root = new JSONObject();
        root.put("format", ConfigBackupService.FORMAT);
        root.put("schemaVersion", ConfigBackupService.SCHEMA_VERSION);
        root.put("sections", sections);
        return root.toJSONString();
    }

    private static JSONObject section(String key, JSONObject... rows) {
        JSONObject sections = new JSONObject();
        sections.put(key, new JSONArray(List.of((Object[]) rows)));
        return sections;
    }

    private static SectionResult result(List<SectionResult> results, BackupSection section) {
        return results.stream().filter(r -> r.getKey().equals(section.key())).findFirst().orElseThrow();
    }

    // ---------------- 导出 ----------------

    @Test
    void 默认导出_密码与敏感参数置空_下载器引用存名字() {
        when(downloaderService.list()).thenReturn(List.of(downloader(7, "家里qB", "p@ss")));
        when(downloaderService.list(anyWrapper())).thenReturn(List.of(downloader(7, "家里qB", "p@ss")));
        PtCleanRulePlus rule = new PtCleanRulePlus();
        rule.setId(3);
        rule.setName("大包");
        rule.setDownloaderId(7);
        when(cleanRuleService.list()).thenReturn(List.of(rule));
        when(configService.selectConfigList(any())).thenReturn(List.of(
                config("openlist.tmdb.apikey", "secret-key"), config("openlist.server.url", "http://ol")));

        JSONObject sections = JSON.parseObject(service.export(false)).getJSONObject("sections");

        JSONObject d = sections.getJSONArray("downloader").getJSONObject(0);
        assertEquals("", d.getString("password"));
        assertFalse(d.containsKey("id"), "自增 id 不该进备份");
        JSONObject r = sections.getJSONArray("cleanRule").getJSONObject(0);
        assertEquals("家里qB", r.getString("downloaderName"));
        assertFalse(r.containsKey("downloaderId"));
        JSONArray configs = sections.getJSONArray("sysConfig");
        assertEquals("", configs.getJSONObject(0).getString("configValue"));
        assertEquals("http://ol", configs.getJSONObject(1).getString("configValue"));
    }

    @Test
    void 勾选含敏感信息_密码原样导出() {
        when(downloaderService.list()).thenReturn(List.of(downloader(7, "家里qB", "p@ss")));

        JSONObject sections = JSON.parseObject(service.export(true)).getJSONObject("sections");

        assertEquals("p@ss", sections.getJSONArray("downloader").getJSONObject(0).getString("password"));
    }

    // ---------------- 恢复：按业务键合并 ----------------

    /** 备份里密码为空表示「没带」，绝不能拿空串冲掉本机已经填好的密码 */
    @Test
    void 同名下载器_备份里密码为空_更新其他字段且保留本机密码() {
        PtDownloaderPlus existing = downloader(7, "家里qB", "local-pass");
        when(downloaderService.list()).thenReturn(List.of(existing));
        JSONObject row = new JSONObject();
        row.put("name", "家里qB");
        row.put("host", "192.168.1.9");
        row.put("password", "");

        List<SectionResult> results = service.restore(backup(section("downloader", row)), Set.of("downloader")).sections();

        assertEquals(1, result(results, BackupSection.DOWNLOADER).getUpdated());
        verify(downloaderService).update(any(PtDownloaderPlus.class), any());
        assertEquals("192.168.1.9", existing.getHost());
        assertEquals("local-pass", existing.getPassword());
    }

    @Test
    void 本机没有的下载器_新增且提示补填密码() {
        JSONObject row = new JSONObject();
        row.put("name", "新 TR");
        row.put("password", "");

        SectionResult r = result(service.restore(backup(section("downloader", row)), Set.of("downloader")).sections(),
                BackupSection.DOWNLOADER);

        assertEquals(1, r.getInserted());
        assertTrue(r.getWarnings().get(0).contains("补填"), r.getWarnings().toString());
        ArgumentCaptor<PtDownloaderPlus> saved = ArgumentCaptor.forClass(PtDownloaderPlus.class);
        verify(downloaderService).save(saved.capture());
        assertNull(saved.getValue().getId());
    }

    @Test
    void 内容相同_计为未变化_不写库() {
        when(downloaderService.list()).thenReturn(List.of(downloader(7, "家里qB", "p")));
        JSONObject row = new JSONObject();
        row.put("name", "家里qB");
        row.put("type", "QBITTORRENT");
        row.put("host", "10.0.0.2");

        SectionResult r = result(service.restore(backup(section("downloader", row)), Set.of("downloader")).sections(),
                BackupSection.DOWNLOADER);

        assertEquals(1, r.getUnchanged());
        verify(downloaderService, never()).update(any(PtDownloaderPlus.class), any());
    }

    /** 两台机器的下载器 id 毫无关系，删种规则必须按名字换成本机的 id */
    @Test
    void 删种规则_按下载器名字换成本机id() {
        when(downloaderService.list(anyWrapper())).thenReturn(List.of(downloader(42, "家里qB", "p")));
        JSONObject row = new JSONObject();
        row.put("name", "大包");
        row.put("downloaderName", "家里qB");

        service.restore(backup(section("cleanRule", row)), Set.of("cleanRule"));

        ArgumentCaptor<PtCleanRulePlus> saved = ArgumentCaptor.forClass(PtCleanRulePlus.class);
        verify(cleanRuleService).save(saved.capture());
        assertEquals(42, saved.getValue().getDownloaderId());
    }

    @Test
    void 删种规则引用的下载器本机没有_跳过并提示() {
        JSONObject row = new JSONObject();
        row.put("name", "大包");
        row.put("downloaderName", "不存在的");

        SectionResult r = result(service.restore(backup(section("cleanRule", row)), Set.of("cleanRule")).sections(),
                BackupSection.CLEAN_RULE);

        assertEquals(1, r.getSkipped());
        assertTrue(r.getWarnings().get(0).contains("不存在的"), r.getWarnings().toString());
        verify(cleanRuleService, never()).save(any());
    }

    @Test
    void 敏感参数为空_保留本机值() {
        SysConfig current = config("openlist.tg.token", "tg-token");
        when(configService.selectConfigList(any())).thenReturn(List.of(current));
        JSONObject row = new JSONObject();
        row.put("configKey", "openlist.tg.token");
        row.put("configValue", "");

        service.restore(backup(section("sysConfig", row)), Set.of("sysConfig"));

        verify(configService, never()).updateConfig(any());
        assertEquals("tg-token", current.getConfigValue());
    }

    @Test
    void 预览不写库() {
        JSONObject row = new JSONObject();
        row.put("name", "新 TR");

        ConfigBackupService.BackupPreview preview = service.preview(backup(section("downloader", row)));

        assertEquals(1, result(preview.sections(), BackupSection.DOWNLOADER).getInserted());
        verify(downloaderService, never()).save(any());
    }

    /** 已有订阅带着本机的下载进度，用备份覆盖上去可能打断正在进行的事 */
    @Test
    void 订阅_已存在的跳过_其余交给后台重建() {
        PtSubscriptionPlus existing = new PtSubscriptionPlus();
        existing.setTmdbId("100");
        existing.setMediaType("TV");
        existing.setSeason(1);
        when(subscriptionService.list()).thenReturn(List.of(existing));
        JSONObject same = new JSONObject();
        same.put("tmdbId", "100");
        same.put("mediaType", "TV");
        same.put("season", 1);
        JSONObject fresh = new JSONObject();
        fresh.put("tmdbId", "100");
        fresh.put("mediaType", "TV");
        fresh.put("season", 2);
        fresh.put("status", "PAUSED");

        ConfigBackupService.RestoreResult result = service.restore(
                backup(section("subscription", same, fresh)), Set.of("subscription"));

        SectionResult r = result(result.sections(), BackupSection.SUBSCRIPTION);
        assertEquals(1, r.getUnchanged());
        assertEquals(1, r.getInserted());
        assertTrue(result.subscriptionsRestoring());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubscriptionRestorer.Seed>> seeds = ArgumentCaptor.forClass(List.class);
        verify(subscriptionRestorer).start(seeds.capture());
        assertEquals(2, seeds.getValue().get(0).season());
        assertEquals("PAUSED", seeds.getValue().get(0).status());
    }

    @Test
    void 未勾选的分区不动() {
        JSONObject row = new JSONObject();
        row.put("name", "新 TR");

        assertThrows(IllegalArgumentException.class,
                () -> service.restore(backup(section("downloader", row)), Set.of("indexer")));
        verify(downloaderService, never()).save(any());
        verify(subscriptionRestorer, never()).start(anyList());
    }

    // ---------------- 文件校验 ----------------

    @Test
    void 不是备份文件_拒绝() {
        assertThrows(IllegalArgumentException.class, () -> ConfigBackupService.parse("{\"a\":1}"));
        assertThrows(IllegalArgumentException.class, () -> ConfigBackupService.parse("not json"));
    }

    @Test
    void 更新版本导出的备份_拒绝并提示升级() {
        String content = "{\"format\":\"osr-backup\",\"schemaVersion\":99,\"sections\":{}}";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ConfigBackupService.parse(content));

        assertTrue(e.getMessage().contains("升级"), e.getMessage());
    }

    @Test
    void 敏感参数判定_名单与后缀兜底() {
        assertTrue(ConfigBackupService.isSecretConfig("openlist.notify.bark.url"));
        assertTrue(ConfigBackupService.isSecretConfig("openlist.some.new.token"));
        assertFalse(ConfigBackupService.isSecretConfig("openlist.server.url"));
    }
}
