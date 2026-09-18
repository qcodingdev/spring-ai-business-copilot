package dev.qcoding.businesscopilot.taskruntime;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;

/** 轻量任务运行时装配：运行记录存储 + 运行管理服务。 */
@AutoConfiguration
public class TaskRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TaskRunStore.class)
    public TaskRunStore taskRunStore(@Qualifier("jdbcTemplate") JdbcTemplate platformJdbcTemplate) {
        return new JdbcTaskRunStore(platformJdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskRunService taskRunService(TaskRunStore taskRunStore,
                                         CurrentActorProvider actorProvider,
                                         ObjectAccessPolicy accessPolicy,
                                         @Value("${business-copilot.task-runtime.stale-attempt-after:PT15M}")
                                         Duration staleAttemptAfter) {
        return new TaskRunService(taskRunStore, actorProvider, accessPolicy,
                Clock.systemUTC(), staleAttemptAfter);
    }

    @Bean
    @ConditionalOnMissingBean(TaskRunController.class)
    public TaskRunController taskRunController(TaskRunStore taskRunStore,
                                               CurrentActorProvider actorProvider) {
        return new TaskRunController(taskRunStore, actorProvider);
    }

    @Bean
    @ConditionalOnMissingBean(TaskRunUserController.class)
    public TaskRunUserController taskRunUserController(TaskRunStore taskRunStore,
                                                       CurrentActorProvider actorProvider,
                                                       TaskRunService taskRunService) {
        return new TaskRunUserController(taskRunStore, actorProvider, taskRunService);
    }
}
