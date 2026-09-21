package com.osr.openliststrm.pt.upgrade;

import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 洗版规则与过滤规则页优先级的一致性。这里每一种失效都是静默的：不报错，
 * 只表现为「洗版一直没发生」或「每个周期都在空搜」。
 */
class UpgradeConfigCheckTest {

    private PtUpgradeConfigPlus upgrade() {
        PtUpgradeConfigPlus c = new PtUpgradeConfigPlus();
        c.setEnabled("1");
        c.setQualityPriority("RESOLUTION,SOURCE,TAG,RELEASE_GROUP");
        c.setTargetResolution("2160p");
        c.setTargetSources("REMUX,BluRay");
        c.setMaxConcurrent(2);
        c.setMaxSearchesPerRound(20);
        c.setScanIntervalHours(6);
        return c;
    }

    private PtFilterConfigPlus filter() {
        PtFilterConfigPlus c = new PtFilterConfigPlus();
        c.setResolutionPriority("2160p,1080p,720p");
        c.setSourcePriority("REMUX,BluRay,WEBDL,HDTV");
        return c;
    }

    @Test
    void 一致的配置_没有问题() {
        assertTrue(UpgradeConfigCheck.errors(upgrade()).isEmpty());
        assertTrue(UpgradeConfigCheck.problems(upgrade(), filter()).isEmpty());
    }

    @Test
    void 默认种子数据_来源优先级为空时被指出() {
        // 出厂配置就是这样：目标来源 REMUX,BluRay，而过滤规则的来源优先级是 NULL
        PtFilterConfigPlus f = filter();
        f.setSourcePriority(null);
        List<String> problems = UpgradeConfigCheck.problems(upgrade(), f);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("媒介来源优先级"));
    }

    @Test
    void 目标分辨率不在优先级里_被指出() {
        // 名次等于列表长度，任何已入库版本都不比它差，全部直接判成达标
        PtUpgradeConfigPlus u = upgrade();
        u.setTargetResolution("4K");
        assertTrue(UpgradeConfigCheck.problems(u, filter()).stream().anyMatch(p -> p.contains("4K")));
    }

    @Test
    void 目标来源不在优先级里_被指出_但BluRay可以代表REMUX() {
        PtUpgradeConfigPlus u = upgrade();
        u.setTargetSources("REMUX");
        PtFilterConfigPlus f = filter();
        f.setSourcePriority("BluRay,WEBDL");
        assertTrue(UpgradeConfigCheck.problems(u, f).isEmpty());

        u.setTargetSources("WEBRip");
        assertFalse(UpgradeConfigCheck.problems(u, f).isEmpty());
    }

    @Test
    void 配了目标标签但维度里没有TAG_被指出() {
        PtUpgradeConfigPlus u = upgrade();
        u.setTargetTags("HDR10");
        u.setQualityPriority("RESOLUTION,SOURCE");
        assertTrue(UpgradeConfigCheck.problems(u, filter()).stream().anyMatch(p -> p.contains("质量标签")));
    }

    @Test
    void 取值越界与空维度_拒绝() {
        PtUpgradeConfigPlus u = upgrade();
        u.setMaxConcurrent(0);
        u.setScanIntervalHours(null);
        u.setMaxSearchesPerRound(1000);
        u.setQualityPriority("");
        List<String> errors = UpgradeConfigCheck.errors(u);
        assertEquals(4, errors.size());
    }

    @Test
    void 开着总开关却没有目标质量_拒绝() {
        PtUpgradeConfigPlus u = upgrade();
        u.setTargetResolution("");
        u.setTargetSources(null);
        assertTrue(UpgradeConfigCheck.errors(u).stream().anyMatch(e -> e.contains("目标质量")));
        u.setEnabled("0");
        assertTrue(UpgradeConfigCheck.errors(u).isEmpty());
    }

    @Test
    void 判定条件变化检测_忽略大小写与空格() {
        PtUpgradeConfigPlus a = upgrade();
        PtUpgradeConfigPlus b = upgrade();
        b.setTargetSources(" remux , bluray ");
        b.setMaxConcurrent(5);
        assertFalse(UpgradeConfigAdminService.criteriaChanged(a, b));
        b.setTargetTags("HDR10");
        assertTrue(UpgradeConfigAdminService.criteriaChanged(a, b));
    }
}
