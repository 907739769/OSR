package com.osr.openliststrm.jobs;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.openliststrm.mybatisplus.domain.*;
import com.osr.openliststrm.mybatisplus.mapper.BackgroundJobPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import com.osr.openliststrm.pt.task.DownloadRecordAdminService;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackgroundJobServiceTest {
    BackgroundJobPlusMapper mapper;
    IPtSubscriptionPlusService subscriptions;
    SearchSupplementService searches;
    BackgroundJobService service;
    final Map<String, BackgroundJobPlus> rows = new ConcurrentHashMap<>();
    @BeforeEach void setup() {
        mapper=mock(BackgroundJobPlusMapper.class); subscriptions=mock(IPtSubscriptionPlusService.class);
        searches=mock(SearchSupplementService.class);
        service=new BackgroundJobService(mapper,subscriptions,searches,mock(DownloadRecordAdminService.class));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(BackgroundJobPlus.class))).thenAnswer(a->{ BackgroundJobPlus row=a.getArgument(0); rows.put(row.getId(),row); return 1; });
        when(mapper.selectById(any(java.io.Serializable.class))).thenAnswer(a->rows.get(a.getArgument(0)));
        when(mapper.update(isNull(),any(Wrapper.class))).thenAnswer(a->{
            UpdateWrapper<BackgroundJobPlus> w=a.getArgument(1);
            String set=w.getSqlSet(); w.getSqlSegment();
            String id=w.getParamNameValuePairs().values().stream().filter(v->v instanceof String && rows.containsKey(v)).map(Object::toString).findFirst().orElse(null);
            for (BackgroundJobPlus row: rows.values()) {
                if (id!=null && !id.equals(row.getId())) continue;
                var matcher=Pattern.compile("(state|items_json|cancel_requested)=#\\{ew.paramNameValuePairs.(\\w+)\\}").matcher(set);
                while(matcher.find()) {
                    Object value=w.getParamNameValuePairs().get(matcher.group(2));
                    switch(matcher.group(1)) {
                        case "state" -> row.setState((String)value);
                        case "items_json" -> row.setItemsJson((String)value);
                        case "cancel_requested" -> row.setCancelRequested((Boolean)value);
                    }
                }
            }
            return 1;
        });
        when(subscriptions.getById(any(java.io.Serializable.class))).thenAnswer(a->{
            PtSubscriptionPlus sub=new PtSubscriptionPlus();sub.setId((Integer)a.getArgument(0));sub.setOwnerUserId(7L);sub.setTitle("样例");return sub;
        });
    }
    @AfterEach void close() { service.shutdown(); }
    BackgroundJobService.Job finish(String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            var job=service.get(id,7L);
            if (!Set.of("QUEUED","RUNNING").contains(job.state())) return job;
            Thread.sleep(10);
        }
        fail("任务未结束");return null;
    }
    @Test void distinctIdsAndRealOutcomesArePersisted() throws Exception {
        var success=new SearchAndPushSummary(false,false,0); success.setEpisodesPushed(2);
        var miss=new SearchAndPushSummary(false,false,0); miss.setRejectSummary("分辨率不符");
        when(searches.searchAndPushMissing(1)).thenReturn(success);
        when(searches.searchAndPushMissing(2)).thenReturn(miss);
        when(searches.searchAndPushMissing(3)).thenReturn(SearchAndPushSummary.skip());
        var job=service.submit(BackgroundJobService.Kind.SUBSCRIPTION_SEARCH,List.of(1,1,2,3),7L);
        var done=finish(job.id());
        assertEquals("PARTIAL",done.state());assertEquals(3,done.items().size());
        assertEquals(List.of("SUCCEEDED","FAILED","SKIPPED"),done.items().stream().map(BackgroundJobService.Item::state).toList());
        assertEquals("分辨率不符",done.items().get(1).message());
        verify(searches,times(1)).searchAndPushMissing(1);
        var retry=service.retry(job.id(),7L);
        assertEquals(List.of(2),retry.items().stream().map(BackgroundJobService.Item::targetId).toList());
        finish(retry.id());
    }
    @Test void ownershipCheckedBeforeCreationAndOnReads() {
        assertThrows(IllegalArgumentException.class,()->service.submit(BackgroundJobService.Kind.SUBSCRIPTION_SEARCH,List.of(1),9L));
        assertTrue(rows.isEmpty());
        BackgroundJobPlus row=new BackgroundJobPlus();row.setId("private");row.setOwnerId(7L);rows.put("private",row);
        assertThrows(IllegalArgumentException.class,()->service.get("private",9L));
        assertThrows(IllegalArgumentException.class,()->service.cancel("private",9L));
        assertThrows(IllegalArgumentException.class,()->service.retry("private",9L));
    }
    @Test void cancellationWaitsForCurrentItemAndDoesNotStartNext() throws Exception {
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        when(searches.searchAndPushMissing(1)).thenAnswer(a->{ started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return SearchAndPushSummary.skip(); });
        var job=service.submit(BackgroundJobService.Kind.SUBSCRIPTION_SEARCH,List.of(1,2),7L);
        assertTrue(started.await(5,TimeUnit.SECONDS));service.cancel(job.id(),7L);release.countDown();
        var done=finish(job.id());assertEquals("CANCELLED",done.state());
        assertEquals("QUEUED",done.items().get(1).state());
        verify(searches,never()).searchAndPushMissing(2);
    }
    @Test void restartMarksPendingWorkInterrupted() {
        BackgroundJobPlus row=new BackgroundJobPlus();row.setId("old");row.setOwnerId(7L);row.setState("RUNNING");row.setItemsJson("[]");rows.put("old",row);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        service.recover();assertEquals("INTERRUPTED",service.get("old",7L).state());
    }
}
