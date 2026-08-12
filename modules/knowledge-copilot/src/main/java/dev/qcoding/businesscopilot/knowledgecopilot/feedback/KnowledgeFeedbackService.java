package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;

import java.util.List;

/** 知识问答质量闭环：提交本人反馈并为人工复核提供问题队列。 */
public class KnowledgeFeedbackService {

    private final KnowledgeFeedbackRepository repository;
    private final CurrentActorProvider actorProvider;
    private final SensitiveTextMasker sensitiveTextMasker;

    public KnowledgeFeedbackService(
            KnowledgeFeedbackRepository repository,
            CurrentActorProvider actorProvider,
            SensitiveTextMasker sensitiveTextMasker) {
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    public KnowledgeAnswerFeedback submit(
            Long answerId,
            KnowledgeAnswerFeedbackRequest request) {
        if (request.rating() == KnowledgeFeedbackRating.NOT_HELPFUL
                && request.reason() == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "认为答案无帮助时必须选择原因");
        }
        KnowledgeFeedbackReason reason = request.rating() == KnowledgeFeedbackRating.HELPFUL
                ? null : request.reason();
        String comment = normalizeComment(request.comment());
        String actorId = actorProvider.currentActor().actorId();
        return repository.upsert(answerId, actorId, request.rating(), reason, comment)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "问答记录不存在或不属于当前操作者"));
    }

    public List<KnowledgeQualityQueueItem> findQualityQueue(int page, int size) {
        return repository.findQualityQueue(page, size);
    }

    public long countQualityQueue() {
        return repository.countQualityQueue();
    }

    public KnowledgeQualityReview review(
            Long answerId,
            KnowledgeQualityReviewRequest request) {
        String reviewNote = normalizeComment(request.reviewNote());
        if (reviewNote == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "人工处置必须填写复核说明");
        }
        String actorId = actorProvider.currentActor().actorId();
        return repository.review(
                        answerId,
                        request.decision(),
                        request.evidenceAssessment(),
                        request.answerAssessment(),
                        request.remediationAction(),
                        reviewNote,
                        actorId,
                        request.expectedIssueVersion(),
                        request.expectedIssueUpdatedAt())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STATE_CONFLICT,
                        "质量问题已被处理或内容已经更新，请刷新队列后重试"));
    }

    public KnowledgeQualityMetrics qualityMetrics() {
        return repository.qualityMetrics();
    }

    private String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        return sensitiveTextMasker.mask(comment.trim());
    }
}
