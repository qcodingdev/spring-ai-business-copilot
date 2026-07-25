package dev.qcoding.businesscopilot.demo;

import java.time.Instant;
import java.util.UUID;

/** 可恢复的虚构数据初始化或恢复任务。 */
public record DemoDataJob(
        UUID id,
        JobType jobType,
        JobStatus status,
        String requestedBy,
        String summaryJson,
        String errorCategory,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public enum JobType { INITIALIZE, RESET }
    public enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
