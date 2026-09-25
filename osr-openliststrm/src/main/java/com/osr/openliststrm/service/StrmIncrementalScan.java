package com.osr.openliststrm.service;

import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmDirSnapshotPlus;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * STRM 增量扫描的判定规则，全是纯函数，方便单测钉住。
 * <p>
 * <b>只跳叶子目录，不跳子树</b>：目录的修改时间只随它<b>直接</b>包含的条目增减而变，不会向上传递。
 * 新一集放进 {@code /电视剧/三体/Season 2/}，变的只有 Season 2；按子树跳过的话，
 * 「已有季里新增一集」这个最常见的情况恰恰会被漏掉。
 *
 * @author Jack
 */
public final class StrmIncrementalScan {

    /** 一次目录级生成的扫描方式 */
    public enum Mode {
        /** 全量、不碰快照：没开增量的任务，以及复制完成触发、TG 指定路径等按路径触发的入口 */
        FULL,
        /** 全量并重建快照：开了增量的任务到了全量周期、或被手动执行 */
        FULL_REBUILD,
        /** 增量：跳过快照对得上的叶子目录 */
        INCREMENTAL
    }

    private StrmIncrementalScan() {
    }

    /**
     * 这次定时执行该不该全量。
     *
     * @param lastFullScan 上次全量完成时间，null 表示从没全量过
     * @param days         全量间隔（天），0 表示每次都全量
     */
    public static boolean fullScanDue(Date lastFullScan, int days, Date now) {
        if (lastFullScan == null || days <= 0) {
            return true;
        }
        return now.getTime() - lastFullScan.getTime() >= TimeUnit.DAYS.toMillis(days);
    }

    /**
     * 网盘返回的修改时间能不能用来判断「没变」。空值和 Go 的零值时间（{@code 0001-01-01...}，
     * 有的驱动对目录一律返回它）都不能用——拿它做快照，这个目录就会被永远跳过直到下次全量。
     */
    public static boolean knownModified(String modified) {
        return StringUtils.isNotBlank(modified) && !modified.startsWith("0001-01-01");
    }

    /** 这个目录这次能不能跳过：有快照、修改时间原样没变、生成设置也没变 */
    public static boolean canSkip(OpenlistStrmDirSnapshotPlus snapshot, String modified, String settingsSign) {
        return snapshot != null
                && knownModified(modified)
                && modified.equals(snapshot.getModified())
                && settingsSign.equals(snapshot.getSettingsSign());
    }

    /**
     * 生成设置的指纹。任何一项变了，已跳过的目录里就可能有「按新设置该生成却没生成」的文件
     * （比如刚打开下载字幕、调低了最小体积、加了一个视频扩展名），快照必须作废。
     * 扩展名先排序，免得配置里换个顺序就让全部快照失效。
     */
    public static String settingsSign(String baseUrl, boolean encode, boolean downloadSub, long minSize,
                                      String outputDir, Set<String> videoExtensions, Set<String> subtitleExtensions) {
        String raw = String.join("\n",
                StringUtils.defaultString(baseUrl),
                String.valueOf(encode),
                String.valueOf(downloadSub),
                String.valueOf(minSize),
                StringUtils.defaultString(outputDir),
                String.join(",", new TreeSet<>(videoExtensions)),
                String.join(",", new TreeSet<>(subtitleExtensions)));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
