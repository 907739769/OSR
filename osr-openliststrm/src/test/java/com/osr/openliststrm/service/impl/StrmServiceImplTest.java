package com.osr.openliststrm.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.helper.StrmHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.osr.openliststrm.service.IStrmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StrmServiceImplTest {

    @Mock
    private IOpenlistStrmPlusService openlistStrmPlusService;

    private StrmServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new StrmServiceImpl();
        ReflectionTestUtils.setField(service, "openlistStrmPlusService", openlistStrmPlusService);
    }

    private static OpenlistStrmPlus row(int id, String path, String name, String status) {
        OpenlistStrmPlus r = new OpenlistStrmPlus();
        r.setStrmId(id);
        r.setStrmPath(path);
        r.setStrmFileName(name);
        r.setStrmStatus(status);
        return r;
    }

    // ------------------------------------------------------------------
    // 子树已有记录的索引
    // ------------------------------------------------------------------

    @Test
    void 索引已有记录_成功记录只进successKeys_不占failedIdByKey() {
        StrmServiceImpl.ExistingIndex index = StrmServiceImpl.indexExisting(List.of(
                row(1, "/媒体/剧集", "a.mkv", "1"),
                row(2, "/媒体/剧集", "b.mkv", "1"),
                row(3, "/媒体/剧集", "c.mkv", "0")));

        assertEquals(2, index.successKeys().size());
        // 能走到落库的文件一定不在 successKeys 里，成功记录的 id 永远查不到，装了纯属浪费
        assertEquals(1, index.failedIdByKey().size(), "failedIdByKey 只该装未成功的记录");
        assertEquals(3, index.failedIdByKey().get(StrmHelper.recordKey("/媒体/剧集", "c.mkv")));
    }

    @Test
    void 索引已有记录_状态为null的历史行必须算作未成功() {
        // strm_status 建表就是 NULL DEFAULT NULL，历史行可能没有状态值。
        // 漏收的话落库时会当成「没有记录」走 insert，凭空多出一行重复记录
        StrmServiceImpl.ExistingIndex index = StrmServiceImpl.indexExisting(
                List.of(row(7, "/媒体/剧集", "老文件.mkv", null)));

        assertTrue(index.successKeys().isEmpty());
        assertEquals(7, index.failedIdByKey().get(StrmHelper.recordKey("/媒体/剧集", "老文件.mkv")));
    }

    // ------------------------------------------------------------------
    // 子树前缀匹配
    // ------------------------------------------------------------------

    @Test
    void 子树前缀_补路径分隔符避免捞进同前缀的兄弟目录() {
        // 不补分隔符时 /电视剧/三体 会连 /电视剧/三体2 一起捞
        assertEquals("/电视剧/三体/", StrmServiceImpl.subtreeLikePrefix("/电视剧/三体"));
    }

    @Test
    void 子树前缀_转义LIKE通配符() {
        // 路径里的 _ 是发布组命名的常态，在 LIKE 里是「任意单字符」
        assertEquals("/媒体\\_库/", StrmServiceImpl.subtreeLikePrefix("/媒体_库"));
        assertEquals("/a\\%b/", StrmServiceImpl.subtreeLikePrefix("/a%b"));
    }

    @Test
    void 子树前缀_根路径退化为匹配全部记录() {
        // 整库任务：规范化成空串，前缀是 /，likeRight 出来就是 LIKE '/%'
        assertEquals("/", StrmServiceImpl.subtreeLikePrefix("/"));
        assertEquals("", StrmServiceImpl.normalizedRoot("/"));
    }

    @Test
    void 子树前缀_末尾斜杠先被规范化掉() {
        // 记录里的 strm_path 经过 listDirCollect 的 removeEnd，不带末尾斜杠，两边口径要一致
        assertEquals("/媒体/剧集", StrmServiceImpl.normalizedRoot("/媒体/剧集/"));
        assertEquals("/媒体/剧集/", StrmServiceImpl.subtreeLikePrefix("/媒体/剧集/"));
    }

    @Test
    void retryAllFailed_没有失败记录_返回0且不触发重试() {
        when(openlistStrmPlusService.count(any(Wrapper.class))).thenReturn(0L);

        IStrmService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(0, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(openlistStrmPlusService, never()).list(any(Wrapper.class));
        verify(openlistStrmPlusService, never()).listByIds(any());
    }

    @Test
    void retryAllFailed_失败记录未超上限_全部提交重试且remaining为0() {
        when(openlistStrmPlusService.count(any(Wrapper.class))).thenReturn(2L);
        OpenlistStrmPlus a = new OpenlistStrmPlus();
        a.setStrmId(5);
        OpenlistStrmPlus b = new OpenlistStrmPlus();
        b.setStrmId(3);
        when(openlistStrmPlusService.list(any(Wrapper.class))).thenReturn(List.of(a, b));
        // retryStrm 内部会再查一次 listByIds 取完整记录；返回空列表即可让内部的异步重试分支安全跑完，
        // 不需要真的执行网络请求，本测试只关心 retryAllFailed 自己的查询与转发逻辑
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of());

        IStrmService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(2, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(openlistStrmPlusService).listByIds(eq(List.of("5", "3")));
    }

    @Test
    void retryAllFailed_失败记录超过200条上限_只取最新200条且remaining正确() {
        when(openlistStrmPlusService.count(any(Wrapper.class))).thenReturn(250L);
        OpenlistStrmPlus a = new OpenlistStrmPlus();
        a.setStrmId(9);
        when(openlistStrmPlusService.list(any(Wrapper.class))).thenReturn(List.of(a));
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of());

        IStrmService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(1, outcome.retried());
        assertEquals(249, outcome.remaining());
    }
    // ------------------------------------------------------------------
    // 任务级覆盖：按路径挑出生效的任务
    // ------------------------------------------------------------------

    private static OpenlistStrmTaskPlus task(int id, String path, String override) {
        OpenlistStrmTaskPlus t = new OpenlistStrmTaskPlus();
        t.setStrmTaskId(id);
        t.setStrmTaskPath(path);
        t.setStrmOverride(override);
        return t;
    }

    @Test
    void 挑任务_没有任务或路径为空时返回null() {
        assertNull(StrmServiceImpl.pickCoveringTask(null, "/电视剧"));
        assertNull(StrmServiceImpl.pickCoveringTask(List.of(), "/电视剧"));
        assertNull(StrmServiceImpl.pickCoveringTask(List.of(task(1, "/电视剧", null)), null));
    }

    @Test
    void 挑任务_精确匹配与子目录都算覆盖() {
        List<OpenlistStrmTaskPlus> tasks = List.of(task(1, "/电视剧", null));

        assertEquals(1, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧").getStrmTaskId());
        assertEquals(1, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧/三体/S01").getStrmTaskId());
    }

    @Test
    void 挑任务_同前缀的兄弟目录不算覆盖() {
        // 与 subtreeLikePrefix 同一个坑：不补分隔符时 /电视剧 会把 /电视剧2 一起吃掉
        List<OpenlistStrmTaskPlus> tasks = List.of(task(1, "/电视剧", null));

        assertNull(StrmServiceImpl.pickCoveringTask(tasks, "/电视剧2"));
        assertNull(StrmServiceImpl.pickCoveringTask(tasks, "/电视剧2/三体"));
    }

    @Test
    void 挑任务_多个都覆盖时取最具体的那个() {
        List<OpenlistStrmTaskPlus> tasks = List.of(
                task(1, "/", null),
                task(2, "/电视剧", null),
                task(3, "/电视剧/日剧", null));

        assertEquals(3, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧/日剧/孤独的美食家").getStrmTaskId());
        assertEquals(2, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧/三体").getStrmTaskId());
        // 根任务兜底：能覆盖一切，但长度最短，永远输给更具体的
        assertEquals(1, StrmServiceImpl.pickCoveringTask(tasks, "/电影/沙丘").getStrmTaskId());
    }

    @Test
    void 挑任务_末尾斜杠不影响匹配() {
        List<OpenlistStrmTaskPlus> tasks = List.of(task(1, "/电视剧/", null));

        assertEquals(1, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧").getStrmTaskId());
        assertEquals(1, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧/").getStrmTaskId());
        assertEquals(1, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧/三体").getStrmTaskId());
    }

    @Test
    void 挑任务_路径重复配置时按id取小保证结果稳定() {
        List<OpenlistStrmTaskPlus> tasks = List.of(
                task(7, "/电视剧", null),
                task(3, "/电视剧", null));

        assertEquals(3, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧/三体").getStrmTaskId());
    }

    @Test
    void 挑任务_停用的任务同样参与匹配() {
        // status 管的是「定时任务要不要自动跑」，覆盖描述的是「这个目录该怎么生成」。
        // 停用后被 TG 手动触发一次就写到另一个根目录，正是要避免的不一致
        OpenlistStrmTaskPlus disabled = task(1, "/电视剧", "{\"outputDir\":\"/data/strm-tv\"}");
        disabled.setStrmTaskStatus("0");

        assertEquals(1, StrmServiceImpl.pickCoveringTask(List.of(disabled), "/电视剧/三体").getStrmTaskId());
    }

    @Test
    void 挑任务_跳过路径为空的脏数据() {
        List<OpenlistStrmTaskPlus> tasks = new java.util.ArrayList<>();
        tasks.add(task(1, null, null));
        tasks.add(null);
        tasks.add(task(2, "/电视剧", null));

        assertEquals(2, StrmServiceImpl.pickCoveringTask(tasks, "/电视剧/三体").getStrmTaskId());
    }
}