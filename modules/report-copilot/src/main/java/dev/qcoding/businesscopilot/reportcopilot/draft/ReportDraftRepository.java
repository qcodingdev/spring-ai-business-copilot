package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Transactional persistence boundary for report requests, their sanitized sources, and a draft. */
public interface ReportDraftRepository {

    ReportDraft save(ReportRequestPreparationService.ReportRequestPreview preview, LlmReportOutput content,
                     String modelName, Duration draftTtl);

    ReportDraft saveNeedsReview(ReportRequestPreparationService.ReportRequestPreview preview,
                                List<String> reviewReasons, String modelName, Duration draftTtl);

    Optional<ReportDraft> findById(Long draftId);

    boolean updateContent(Long draftId, ReportDraftStatus expected, LlmReportOutput content,
                          String actionActorId);

    boolean transitionStatus(Long draftId, ReportDraftStatus expected, ReportDraftStatus target,
                             String actionActorId);
}
