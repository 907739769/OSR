package com.osr.openliststrm.pt;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.subscription.SubscriptionMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PtLogText} 的称呼规则。
 */
class PtLogTextTest {

    private static PtSubscriptionPlus tv(String title) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(148);
        sub.setTitle(title);
        sub.setMediaType("TV");
        sub.setSeason(1);
        return sub;
    }

    @Test
    @DisplayName("片名里的 & 原样保留——通知那份会转义成 &amp;，日志不该跟着转")
    void ampersandIsNotEscaped() {
        // & 在片名和组名里相当常见，这正是不能直接复用 PtNotifyText 的原因
        String s = PtLogText.subject(tv("Tom & Jerry"), 5, null);
        assertTrue(s.contains("Tom & Jerry"), s);
        assertTrue(!s.contains("&amp;"), "日志没有 parse_mode，不该转义：" + s);
    }

    @Test
    @DisplayName("剧集带季集号，id 退到方括号里但必须留着")
    void tvSubjectCarriesSeasonEpisodeAndId() {
        // 剧名给人看，id 给「拿去查库」用，两个都要
        assertEquals("《闪耀的她》 S01E05[#148]", PtLogText.subject(tv("闪耀的她"), 5, null));
    }

    @Test
    @DisplayName("电影不写季集号")
    void movieHasNoEpisode() {
        PtSubscriptionPlus movie = tv("功夫");
        movie.setMediaType("MOVIE");
        assertEquals("《功夫》[#148]", PtLogText.subject(movie, null, null));
    }

    @Test
    @DisplayName("季包和区间集各有说法")
    void seasonPackAndRange() {
        assertEquals("《航海王》 S01 全季[#148]",
                PtLogText.subject(tv("航海王"), SubscriptionMatcher.SEASON_PACK, null));
        assertEquals("《航海王》 S01E05-E08[#148]", PtLogText.subject(tv("航海王"), 5, 8));
    }

    @Test
    @DisplayName("标题为空写「未命名」，不是字面量 null")
    void blankTitleFallsBack() {
        // 「《null》」看起来像代码 bug，会把读日志的人引到错误的方向
        PtSubscriptionPlus noTitle = tv(null);
        assertEquals("《未命名》 S01E03[#148]", PtLogText.subject(noTitle, 3, null));
    }

    @Test
    @DisplayName("订阅为 null 返回空串，不抛异常")
    void nullSubscriptionIsBlank() {
        // 日志里的兜底绝不能反过来把主流程炸掉
        assertEquals("", PtLogText.subject((PtSubscriptionPlus) null, 1, null));
    }

    @Test
    @DisplayName("连续集号压成区间，季包几十集也只占一小段")
    void consecutiveNumbersCollapse() {
        assertEquals("1-24", PtLogText.numbers(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)));
    }

    @Test
    @DisplayName("两集不压区间——「5-6」和「5、6」一样长，后者不用读者反应")
    void twoElementRunsStaySpelledOut() {
        assertEquals("5、6", PtLogText.numbers(List.of(5, 6)));
        assertEquals("5-7", PtLogText.numbers(List.of(5, 6, 7)));
    }

    @Test
    @DisplayName("断开的几段各自成区间，顺序与去重都做掉")
    void gapsSplitIntoSegments() {
        assertEquals("1-3、7、10-12", PtLogText.numbers(List.of(11, 2, 1, 3, 7, 10, 12, 2)));
    }

    @Test
    @DisplayName("空集合返回空串")
    void emptyIsBlank() {
        assertEquals("", PtLogText.numbers(List.of()));
        assertEquals("", PtLogText.numbers(null));
    }
}
