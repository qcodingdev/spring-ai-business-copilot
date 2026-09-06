package dev.qcoding.businesscopilot.governance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Evaluation jobs use a bounded worker pool and never execute arbitrary shell commands. */
@Configuration(proxyBeanMethods = false)
public class EvaluationGovernanceConfiguration {

    @Bean
    TaskExecutor evaluationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("evaluation-");
        executor.initialize();
        return executor;
    }
}
