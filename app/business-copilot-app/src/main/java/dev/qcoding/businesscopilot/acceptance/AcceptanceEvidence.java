package dev.qcoding.businesscopilot.acceptance;

import java.time.Instant;

/**
 * 分层验收证据模型（CORE-05）。
 *
 * <p>四类验收证据分别记录、分别显示状态：运行就绪（READY）不替代模型质量、
 * 供应商验收或正式发布门禁。缺少凭据、环境未启动或测试跳过的情况一律
 * {@link Status#NOT_VERIFIED}，不能记为通过。</p>
 */
public final class AcceptanceEvidence {

    private AcceptanceEvidence() {
    }

    /** 验收证据类别；每类独立判定，互不替代。 */
    public enum Category {
        /** 本地/CI 运行就绪检查（来自 EnterpriseReadiness 评估）。 */
        RUNTIME_READINESS,
        /** 任务级模型质量评测（Evaluation Harness 报告）。 */
        MODEL_QUALITY,
        /** 供应商沙箱验收证据。 */
        VENDOR_ACCEPTANCE,
        /** 正式发布门禁证据。 */
        RELEASE_GATE
    }

    /** 证据状态：未验证不算通过。 */
    public enum Status {
        PASS,
        ATTENTION,
        FAILED,
        NOT_VERIFIED
    }

    /** 单条证据：来源、适用版本与记录时间；说明不包含敏感凭据。 */
    public record Evidence(
            Long id,
            Category category,
            String name,
            Status status,
            String source,
            String applicableVersion,
            String note,
            String recordedBy,
            Instant recordedAt) {
    }

    /** 同版本、同类别中，每项检查取最新记录；最差状态代表该类，未验证阻断通过。 */
    public record CategorySummary(Category category, Status status, int evidenceCount,
                                  String summary, String summaryEn) {
        public String bilingualSummary() {
            return summary + " / " + summaryEn;
        }
    }

    /**
     * 发布视角的总判定：四类全部 PASS 才允许发布；
     * 运行就绪 READY 不能替代其他类别（验收要点）。
     */
    public record ReleaseReadiness(
            boolean releasable,
            Status overall,
            java.util.List<CategorySummary> categories,
            String blockingReason) {

        public String blockingReasonEn() {
            return blockingReason == null ? null
                    : blockingReason.replace("未通过", "not passed").replace("未验证", "not verified");
        }
    }
}
