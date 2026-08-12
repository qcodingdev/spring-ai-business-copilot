package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import java.util.List;
import java.util.Optional;

/** 知识问答反馈和质量队列的持久化边界。 */
public interface KnowledgeFeedbackRepository {

    Optional<KnowledgeAnswerFeedback> upsert(
            Long answerId,
            String actorId,
            KnowledgeFeedbackRating rating,
            KnowledgeFeedbackReason reason,
            String comment);

    List<KnowledgeQualityQueueItem> findQualityQueue(int page, int size);

    long countQualityQueue();

    Optional<KnowledgeQualityReview> review(
            Long answerId,
            KnowledgeQualityReviewDecision decision,
            KnowledgeEvidenceAssessment evidenceAssessment,
            KnowledgeAnswerAssessment answerAssessment,
            KnowledgeRemediationAction remediationAction,
            String reviewNote,
            String reviewerActorId,
            long expectedIssueVersion,
            java.time.Instant expectedIssueUpdatedAt);

    KnowledgeQualityMetrics qualityMetrics();
}
