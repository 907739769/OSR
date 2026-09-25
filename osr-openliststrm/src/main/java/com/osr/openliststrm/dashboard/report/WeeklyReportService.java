package com.osr.openliststrm.dashboard.report;

import com.alibaba.fastjson2.JSON;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.dashboard.stats.DashboardStatsService;
import com.osr.openliststrm.dashboard.stats.dto.DashboardTrendPointDTO;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.openai.AiChatService;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.stats.PtStatsService;
import com.osr.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsFailReasonDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每周周报：最近 7 天的同步 / STRM / 重命名 / PT 下载概况，配了 OpenAI 时再附一段 AI 点评。
 * <p>
 * 由定时任务「openliststrm-每周周报」（{@code openListStrmTask.weeklyReport()}，默认每周一 9 点、默认暂停）触发，
 * 走通知路由的 {@link NotificationType#WEEKLY_REPORT}，用户可以在「通知路由」里单独关掉。
 * </p>
 * <p>
 * <b>数字全部来自现成的统计口径</b>（首页趋势 {@link DashboardStatsService}、PT 统计 {@link PtStatsService}），
 * 不另写一套 SQL——周报与页面上的数字对不上，用户会开始怀疑哪个是真的。
 * <b>AI 只写点评，不产出数字</b>：数字段落由代码拼好，AI 拿到的是同一份事实，让它编数字没有任何好处。
 * AI 调用失败时照发不带点评的版本。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class WeeklyReportService {

    static final int DAYS = 7;

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    private final DashboardStatsService dashboardStats;
    private final PtStatsService ptStats;
    private final AiChatService ai;

    public WeeklyReportService(DashboardStatsService dashboardStats, PtStatsService ptStats, AiChatService ai) {
        this.dashboardStats = dashboardStats;
        this.ptStats = ptStats;
        this.ai = ai;
    }

    /** 本周数据的汇总，拼文案与喂给 AI 的是同一份 */
    record Facts(String range, Counts copy, Counts strm, Counts rename, PtStatsOverviewDTO pt,
                 List<PtStatsFailReasonDTO> failReasons, List<PtStatsActiveSubscriptionDTO> topSubs) {
    }

    record Counts(long total, long success, long failed) {
        static Counts of(List<DashboardTrendPointDTO> points) {
            long t = 0, s = 0, f = 0;
            for (DashboardTrendPointDTO p : points) {
                t += p.getTotalCount();
                s += p.getSuccessCount();
                f += p.getFailedCount();
            }
            return new Counts(t, s, f);
        }
    }

    /** 生成并发送，返回发出去的文案（供手动执行时回显） */
    public String send() {
        String text = build();
        TgHelper.sendMsg(NotificationType.WEEKLY_REPORT, text);
        log.info("每周周报已发送（{} 字）", text.length());
        return text;
    }

    String build() {
        Facts facts = collect();
        String body = render(facts);
        String comment = aiComment(facts);
        return comment == null ? body : body + "\n\n<b>AI 点评</b>\n" + StringUtils.escapeHtml(comment);
    }

    Facts collect() {
        LocalDate today = LocalDate.now();
        String range = today.minusDays(DAYS - 1L).format(MD) + " ~ " + today.format(MD);
        List<PtStatsFailReasonDTO> reasons = ptStats.failReasons(DAYS, PtStatsScope.ALL);
        return new Facts(range,
                Counts.of(dashboardStats.trend("copy", DAYS)),
                Counts.of(dashboardStats.trend("strm", DAYS)),
                Counts.of(dashboardStats.trend("rename", DAYS)),
                ptStats.overview(DAYS, PtStatsScope.ALL),
                reasons.size() > 3 ? reasons.subList(0, 3) : reasons,
                ptStats.topSubscriptions(DAYS, 5, PtStatsScope.ALL));
    }

    static String render(Facts f) {
        StringBuilder sb = new StringBuilder("📊 <b>OSR 周报</b>（").append(f.range()).append("）\n");
        sb.append("\n<b>文件</b>\n");
        line(sb, "同步", f.copy());
        line(sb, "STRM", f.strm());
        line(sb, "重命名", f.rename());

        PtStatsOverviewDTO pt = f.pt();
        sb.append("\n<b>PT</b>\n");
        sb.append("推送 ").append(pt.getTotalDownloadRecords())
                .append(" · 完成 ").append(pt.getCompletedCount())
                .append(" · 未解决的失败 ").append(pt.getFailedCount());
        if (pt.getCompletedCount() + pt.getFailedCount() > 0) {
            sb.append(" · 成功率 ").append(pt.getSuccessRate()).append('%');
        }
        sb.append("\n订阅中 ").append(pt.getActiveSubscriptions()).append(" / 共 ").append(pt.getTotalSubscriptions());
        if (pt.getHrViolatedCount() > 0) {
            sb.append("\n⚠️ H&amp;R 未达标 ").append(pt.getHrViolatedCount()).append(" 个");
        }
        if (!f.failReasons().isEmpty()) {
            sb.append("\n失败原因：");
            for (int i = 0; i < f.failReasons().size(); i++) {
                PtStatsFailReasonDTO r = f.failReasons().get(i);
                sb.append(i == 0 ? "" : "、").append(StringUtils.escapeHtml(r.getReason())).append(' ').append(r.getCount());
            }
        }
        List<PtStatsActiveSubscriptionDTO> active = f.topSubs().stream().filter(s -> s.getCompletedCount() > 0).toList();
        if (!active.isEmpty()) {
            sb.append("\n本周入库最多：");
            for (int i = 0; i < active.size(); i++) {
                PtStatsActiveSubscriptionDTO s = active.get(i);
                sb.append(i == 0 ? "" : "、").append('《').append(StringUtils.escapeHtml(s.getTitle())).append('》')
                        .append(s.getCompletedCount()).append(" 个");
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, Counts c) {
        sb.append(label).append("：");
        if (c.total() == 0) {
            sb.append("无记录\n");
            return;
        }
        sb.append("成功 ").append(c.success()).append(" · 失败 ").append(c.failed());
        if (c.failed() > 0) {
            sb.append(" ❗");
        }
        sb.append('\n');
    }

    private String aiComment(Facts f) {
        if (!ai.available()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("时间范围", f.range());
        data.put("同步", f.copy());
        data.put("STRM", f.strm());
        data.put("重命名", f.rename());
        data.put("PT概况", f.pt());
        data.put("PT失败原因", f.failReasons());
        data.put("PT活跃订阅", f.topSubs());
        String prompt = "下面是一个家用影视自动化系统（网盘同步、STRM 生成、PT 订阅下载）最近 7 天的统计 JSON。"
                + "请用中文写 2~4 句简短点评：指出值得注意的变化或问题，给出一条最值得做的处理建议；一切正常就简单说一切正常。"
                + "不要复述全部数字，不要编造数据里没有的信息，不要用 Markdown 或标题，直接输出正文。\n"
                + JSON.toJSONString(data);
        String reply = ai.chat("每周周报点评", prompt, 400);
        return StringUtils.isBlank(reply) ? null : reply.trim();
    }
}
