package dev.qcoding.businesscopilot.taskruntime;

import java.time.Instant;
import java.util.List;

/**
 * 任务的有界上下文声明（RUN-07）。
 *
 * <p>每个任务明确数据范围、授权来源和保留时间；恢复时上下文清单失效
 * （过期或授权来源不可用）则失效内容退出上下文，必须重新获取证据后才能继续。</p>
 *
 * @param dataScopeRefs       本任务允许接触的数据对象标识（表、文档、交接等）
 * @param authorizationSource 授权来源描述（如确认凭证 ID、审批单号）
 * @param retention           上下文保留时长
 * @param expiresAt           上下文过期时间；缺失时不能恢复
 */
public record ContextManifest(List<String> dataScopeRefs,
                              String authorizationSource,
                              java.time.Duration retention,
                              Instant expiresAt) {

    public ContextManifest {
        dataScopeRefs = dataScopeRefs == null ? List.of() : List.copyOf(dataScopeRefs);
    }

    /** 恢复或继续执行前校验上下文是否仍然有效。 */
    public boolean isValid(Instant now) {
        return !dataScopeRefs.isEmpty() && dataScopeRefs.stream().noneMatch(ref -> ref == null || ref.isBlank())
                && authorizationSource != null && !authorizationSource.isBlank()
                && retention != null && !retention.isNegative() && !retention.isZero()
                && expiresAt != null && now.isBefore(expiresAt);
    }
}
