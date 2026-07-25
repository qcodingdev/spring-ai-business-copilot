package dev.qcoding.businesscopilot.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Demo 初始化使用独立单线程，避免占用 Web 请求线程或并发建立重复索引。 */
@Configuration(proxyBeanMethods = false)
public class DemoDataConfiguration {

    @Bean
    TaskExecutor demoDataTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("demo-data-");
        executor.initialize();
        return executor;
    }
}
