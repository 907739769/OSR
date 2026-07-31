package com.osr.common.config.thread;

import com.osr.common.utils.Threads;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * 线程池配置
 *
 * @author osr
 **/
@Configuration
public class ThreadPoolConfig {

    /**
     * 虚拟线程定时执行器 - 支持延迟和周期性调度
     * 基于虚拟线程 + ScheduledThreadPoolExecutor 实现
     * 调度器本身用少量平台线程，任务体在虚拟线程上执行
     */
    @Bean(name = "virtualScheduledExecutor")
    public TaskScheduler virtualScheduledExecutor() {
        // SimpleAsyncTaskScheduler 是 Spring 为虚拟线程专门设计的调度器
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        // 开启虚拟线程支持
        scheduler.setVirtualThreads(true);
        // 设置线程名称前缀，方便日志排查
        scheduler.setThreadNamePrefix("vt-schedule-");
        scheduler.setTaskDecorator(Threads::wrap);
        // 遇到异常时的处理（替代你之前的 afterExecute 打印）
        scheduler.setErrorHandler(t -> System.err.println("定时任务执行异常: " + t.getMessage()));

        return scheduler;
    }


}
