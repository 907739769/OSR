package com.osr.openliststrm.jobs;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.mybatisplus.domain.*;
import com.osr.openliststrm.mybatisplus.mapper.BackgroundJobPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.task.DownloadRecordAdminService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/** 持久化手动长任务。全局串行，避免多批补搜一起压满索引器。 */
@Slf4j
@Service
public class BackgroundJobService {
    public enum Kind { SUBSCRIPTION_SEARCH, DOWNLOAD_RETRY }
    public enum State { QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCELLED, INTERRUPTED }
    public record Item(Integer targetId, String label, String state, String message) {}
    public record Job(String id, String kind, String state, boolean cancelRequested,
                      String createTime, String updateTime, List<Item> items) {}
    private final BackgroundJobPlusMapper mapper;
    private final IPtSubscriptionPlusService subscriptions;
    private final SearchSupplementService searches;
    private final DownloadRecordAdminService downloads;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("manual-job-", 0).factory());

    public BackgroundJobService(BackgroundJobPlusMapper mapper, IPtSubscriptionPlusService subscriptions,
                                SearchSupplementService searches, DownloadRecordAdminService downloads) {
        this.mapper = mapper;
        this.subscriptions = subscriptions;
        this.searches = searches;
        this.downloads = downloads;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // 服务重启时无法知道最后一项是否已推送，保留结果并要求用户显式重试，避免重复下载。
        for (var row : mapper.selectList(new QueryWrapper<BackgroundJobPlus>().in("state", "QUEUED", "RUNNING"))) {
            List<Item> items = view(row).items().stream().map(item -> Set.of("QUEUED", "RUNNING").contains(item.state())
                    ? new Item(item.targetId(), item.label(), "INTERRUPTED", "服务重启前未完成，请对账后重试") : item).toList();
            persist(row.getId(), State.INTERRUPTED, items);
        }
    }

    public List<Job> list(Long owner) {
        return mapper.selectList(new QueryWrapper<BackgroundJobPlus>().eq("owner_id", owner)
                .orderByDesc("create_time", "id").last("LIMIT 50")).stream().map(this::view).toList();
    }

    public Job get(String id, Long owner) { return view(owned(id, owner)); }

    public synchronized Job submit(Kind kind, List<Integer> requested, Long owner) {
        if (owner == null || requested == null || requested.isEmpty() || requested.size() > 200
                || requested.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("请选择 1 至 200 个有效项目");
        if (mapper.selectCount(new QueryWrapper<BackgroundJobPlus>().eq("owner_id", owner)
                .in("state", "QUEUED", "RUNNING")) >= 3)
            throw new IllegalArgumentException("已有 3 个任务等待或执行中，请稍后再试");
        if (mapper.selectCount(new QueryWrapper<BackgroundJobPlus>().in("state", "QUEUED", "RUNNING")) >= 20)
            throw new IllegalArgumentException("后台队列已满，请稍后再试");
        List<Item> items = new ArrayList<>();
        for (Integer id : new LinkedHashSet<>(requested)) {
            verify(kind, id, owner);
            PtSubscriptionPlus sub = kind == Kind.SUBSCRIPTION_SEARCH ? subscriptions.getById(id) : null;
            items.add(new Item(id, sub == null ? "下载记录 #" + id : sub.getTitle(), "QUEUED", "等待执行"));
        }
        BackgroundJobPlus row = new BackgroundJobPlus();
        row.setId(UUID.randomUUID().toString()); row.setOwnerId(owner);
        row.setKind(kind.name()); row.setState(State.QUEUED.name());
        row.setItemsJson(JSON.toJSONString(items)); row.setCancelRequested(false);
        mapper.insert(row);
        worker.submit(Threads.wrap(() -> run(row.getId())));
        return view(row);
    }

    public synchronized Job cancel(String id, Long owner) {
        BackgroundJobPlus row = owned(id, owner);
        if (Set.of("QUEUED", "RUNNING").contains(row.getState())) {
            mapper.update(null, new UpdateWrapper<BackgroundJobPlus>().eq("id", id)
                    .in("state", "QUEUED", "RUNNING").set("cancel_requested", true));
        }
        return get(id, owner);
    }

    public synchronized Job retry(String id, Long owner) {
        BackgroundJobPlus row = owned(id, owner);
        if (Set.of("QUEUED", "RUNNING").contains(row.getState()))
            throw new IllegalArgumentException("请等待任务结束后重试");
        List<Integer> ids = view(row).items().stream().filter(item -> !Set.of("SUCCEEDED", "SKIPPED").contains(item.state()))
                .map(Item::targetId).toList();
        if (ids.isEmpty()) throw new IllegalArgumentException("没有需要重试的项目");
        return submit(Kind.valueOf(row.getKind()), ids, owner);
    }

    private BackgroundJobPlus owned(String id, Long owner) {
        BackgroundJobPlus row = mapper.selectById(id);
        if (row == null || owner == null || !owner.equals(row.getOwnerId()))
            throw new IllegalArgumentException("任务不存在或无权访问");
        return row;
    }

    private void verify(Kind kind, Integer id, Long owner) {
        if (kind == Kind.DOWNLOAD_RETRY) {
            if (!downloads.canAccess(id, PtStatsScope.of(SysUser.isAdmin(owner), owner)))
                throw new IllegalArgumentException("下载记录不存在或无权访问");
        } else {
            PtSubscriptionPlus sub = subscriptions.getById(id);
            if (sub == null || !(SysUser.isAdmin(owner) || sub.getOwnerUserId() == null || owner.equals(sub.getOwnerUserId())))
                throw new IllegalArgumentException("订阅不存在或无权访问");
        }
    }

    // 包可见以便测试真实的排队状态机；只替换底层搜索/持久化边界。
    void run(String id) {
        BackgroundJobPlus row = mapper.selectById(id);
        if (row == null) return;
        List<Item> items = new ArrayList<>(view(row).items());
        try {
            persist(id, State.RUNNING, items);
            for (int i = 0; i < items.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return;
                if (Boolean.TRUE.equals(mapper.selectById(id).getCancelRequested())) {
                    persist(id, State.CANCELLED, items); return;
                }
                Item item = items.get(i);
                items.set(i, new Item(item.targetId(), item.label(), "RUNNING", "执行中"));
                persist(id, State.RUNNING, items);
                try {
                    Kind kind = Kind.valueOf(row.getKind());
                    verify(kind, item.targetId(), row.getOwnerId()); // 执行时再次校验归属
                    items.set(i, execute(kind, item));
                } catch (IllegalArgumentException ex) {
                    items.set(i, new Item(item.targetId(), item.label(), "FAILED", ex.getMessage()));
                } catch (Exception ex) {
                    log.error("后台任务 {} 项目 {} 执行失败：{}", id, item.targetId(), ex.getMessage(), ex);
                    items.set(i, new Item(item.targetId(), item.label(), "FAILED", "执行失败，请查看服务器日志"));
                }
                persist(id, State.RUNNING, items);
            }
            long failed = items.stream().filter(item -> "FAILED".equals(item.state())).count();
            persist(id, failed == 0 ? State.SUCCEEDED : failed == items.size() ? State.FAILED : State.PARTIAL, items);
        } catch (Exception ex) {
            // 数据库不可用时不再继续发起搜索。保留最后一次落库进度，重启后标为中断。
            log.error("后台任务 {} 中断：{}", id, ex.getMessage(), ex);
            try { persist(id, State.INTERRUPTED, items); }
            catch (Exception persistenceError) { log.error("后台任务 {} 无法保存中断状态：{}", id, persistenceError.getMessage(), persistenceError); }
        }
    }

    private Item execute(Kind kind, Item item) {
        if (searches.hasNoEnabledIndexer()) throw new IllegalArgumentException("没有启用中的索引器，请先配置索引器");
        if (kind == Kind.DOWNLOAD_RETRY) {
            var result = downloads.retry(item.targetId());
            return new Item(item.targetId(), item.label(), result.isPushed() ? "SUCCEEDED" : "FAILED",
                    result.isPushed() ? "已推送资源" : Objects.requireNonNullElse(result.getReason(), "未推送任何资源"));
        }
        var result = searches.searchAndPushMissing(item.targetId());
        String state = result.isSkipped() ? "SKIPPED" : result.anyPushed() ? "SUCCEEDED" : "FAILED";
        String message = result.isSkipped() ? "没有可搜索的已播缺集，或订阅已暂停" : result.anyPushed()
                ? "已推送 " + (result.getEpisodesPushed() + (result.isSeasonPushed() ? 1 : 0)) + " 个资源"
                : Objects.requireNonNullElse(result.getRejectSummary(), "未搜到候选资源，请检查标题、季号和索引器");
        return new Item(item.targetId(), item.label(), state, message);
    }

    private void persist(String id, State state, List<Item> items) {
        // 不写 cancel_requested，避免执行线程的旧快照覆盖用户刚提交的中止请求。
        mapper.update(null, new UpdateWrapper<BackgroundJobPlus>().eq("id", id)
                .set("state", state.name()).set("items_json", JSON.toJSONString(items)));
    }

    private Job view(BackgroundJobPlus row) {
        return new Job(row.getId(), row.getKind(), row.getState(), Boolean.TRUE.equals(row.getCancelRequested()),
                row.getCreateTime(), row.getUpdateTime(), JSON.parseArray(row.getItemsJson(), Item.class));
    }

    @PreDestroy
    public void shutdown() { worker.shutdownNow(); }
}
