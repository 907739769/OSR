package com.osr.quartz.config;

import org.quartz.Scheduler;
import org.quartz.SchedulerFactory;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 定时任务配置（内存模式）
 * 
 * @author osr
 */
@Configuration
public class ScheduleConfig
{
    @Bean
    public Scheduler scheduler() throws Exception
    {
        SchedulerFactory schedulerFactory = new StdSchedulerFactory();
        return schedulerFactory.getScheduler();
    }
}
