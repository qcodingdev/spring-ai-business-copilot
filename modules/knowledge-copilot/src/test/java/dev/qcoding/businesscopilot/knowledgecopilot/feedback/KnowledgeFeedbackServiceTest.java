package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeFeedbackServiceTest {

    private KnowledgeFeedbackRepository repository;
    private KnowledgeFeedbackService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeFeedbackRepository.class);
        CurrentActorProvider actorProvider =
                () -> new CurrentActor("operator-1", Set.of());
        service = new KnowledgeFeedbackService(
                repository, actorProvider, new SensitiveTextMasker());
    }

    @Test
    void negativeFeedbackRequiresAStableReason() {
        var request = new KnowledgeAnswerFeedbackRequest(
                KnowledgeFeedbackRating.NOT_HELPFUL, null, "缺少数据");

        assertThatThrownBy(() -> service.submit(7L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void helpfulFeedbackClearsNegativeReasonAndTrimsComment() {
        var saved = new KnowledgeAnswerFeedback(
                1L, 7L, "operator-1", KnowledgeFeedbackRating.HELPFUL,
                null, "已解决", Instant.now(), Instant.now());
        when(repository.upsert(
                eq(7L), eq("operator-1"), eq(KnowledgeFeedbackRating.HELPFUL),
                eq(null), any())).thenReturn(Optional.of(saved));

        KnowledgeAnswerFeedback result = service.submit(
                7L,
                new KnowledgeAnswerFeedbackRequest(
                        KnowledgeFeedbackRating.HELPFUL,
                        KnowledgeFeedbackReason.OTHER,
                        "  已解决  "));

        assertThat(result).isEqualTo(saved);
        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(
                eq(7L), eq("operator-1"), eq(KnowledgeFeedbackRating.HELPFUL),
                eq(null), comment.capture());
        assertThat(comment.getValue()).isEqualTo("已解决");
    }

    @Test
    void feedbackCannotBeAttachedToAnotherActorsAnswer() {
        when(repository.upsert(
                eq(9L), eq("operator-1"), eq(KnowledgeFeedbackRating.NOT_HELPFUL),
                eq(KnowledgeFeedbackReason.INCORRECT), eq(null)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(
                9L,
                new KnowledgeAnswerFeedbackRequest(
                        KnowledgeFeedbackRating.NOT_HELPFUL,
                        KnowledgeFeedbackReason.INCORRECT,
                        null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void freeFormFeedbackIsMaskedBeforePersistence() {
        var saved = new KnowledgeAnswerFeedback(
                1L, 7L, "operator-1", KnowledgeFeedbackRating.NOT_HELPFUL,
                KnowledgeFeedbackReason.OTHER, "联系 138****8000，token=********",
                Instant.now(), Instant.now());
        when(repository.upsert(
                eq(7L), eq("operator-1"), eq(KnowledgeFeedbackRating.NOT_HELPFUL),
                eq(KnowledgeFeedbackReason.OTHER),
                eq("联系 138****8000，token=********"))).thenReturn(Optional.of(saved));

        var result = service.submit(
                7L,
                new KnowledgeAnswerFeedbackRequest(
                        KnowledgeFeedbackRating.NOT_HELPFUL,
                        KnowledgeFeedbackReason.OTHER,
                        "联系 13800138000，token=raw-secret"));

        assertThat(result.comment()).doesNotContain("13800138000", "raw-secret");
    }

    @Test
    void qualityReviewRequiresAUsefulNote() {
        var request = new KnowledgeQualityReviewRequest(
                KnowledgeQualityReviewDecision.RESOLVED,
                " ",
                1L,
                Instant.now());

        assertThatThrownBy(() -> service.review(7L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void qualityReviewBindsReviewerAndIssueVersion() {
        Instant issueUpdatedAt = Instant.now();
        var saved = new KnowledgeQualityReview(
                5L, 7L, KnowledgeQualityReviewDecision.KNOWLEDGE_UPDATE_REQUIRED,
                "补充制度", "operator-1", 1L,
                issueUpdatedAt, Instant.now(), Instant.now());
        when(repository.review(
                eq(7L),
                eq(KnowledgeQualityReviewDecision.KNOWLEDGE_UPDATE_REQUIRED),
                eq("补充制度"),
                eq("operator-1"),
                eq(1L),
                eq(issueUpdatedAt))).thenReturn(Optional.of(saved));

        var result = service.review(
                7L,
                new KnowledgeQualityReviewRequest(
                        KnowledgeQualityReviewDecision.KNOWLEDGE_UPDATE_REQUIRED,
                        "  补充制度  ",
                        1L,
                        issueUpdatedAt));

        assertThat(result).isEqualTo(saved);
    }

    @Test
    void staleOrReplayedQualityReviewFailsClosed() {
        Instant issueUpdatedAt = Instant.now();
        when(repository.review(
                eq(7L),
                eq(KnowledgeQualityReviewDecision.DISMISSED),
                eq("无需处理"),
                eq("operator-1"),
                eq(1L),
                eq(issueUpdatedAt))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(
                7L,
                new KnowledgeQualityReviewRequest(
                        KnowledgeQualityReviewDecision.DISMISSED,
                        "无需处理",
                        1L,
                        issueUpdatedAt)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.STATE_CONFLICT));
    }
}
