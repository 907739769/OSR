package com.ruoyi.openliststrm.pt.subscription;

import com.ruoyi.common.utils.Threads;
import com.ruoyi.common.utils.spring.SpringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 建订阅后一次性补搜历史资源的调度壳子：只负责把
 * {@link SearchSupplementService#supplementOnCreate(Integer)} 丢到虚拟线程异步执行，
 * 不含任何业务判断逻辑（风格同 {@code pt.task.AutoSearchTask}）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class SubscriptionSearchOnCreateTrigger {

    @Autowired
    private SearchSupplementService searchSupplementService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /**
     * 异步发起一次建订阅补搜，立即返回，不等待搜索完成。
     */
    public void triggerAsync(Integer subId) {
        scheduler.schedule(Threads.wrap(() -> {
            try {
                searchSupplementService.supplementOnCreate(subId);
            } catch (Exception e) {
                log.warn("订阅[{}] 建订阅后补搜历史资源失败：{}", subId, e.getMessage());
            }
        }), Instant.now());
    }
}
