package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportOutputSanitizer;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;

/** Owner-authorized digest-backed confirmation and cancellation for report drafts. */
public class ReportDraftConfirmationService {

    private static final Duration REVIEW_SESSION_TTL = Duration.ofMinutes(30);

    private final ReportDraftRepository draftRepository;
    private final ReportAuditService auditService;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final ConfirmationTokenService tokenService;
    private final ReportOutputSanitizer outputSanitizer;

    public ReportDraftConfirmationService(ReportDraftRepository draftRepository,
                                          ReportAuditService auditService,
                                          CurrentActorProvider actorProvider,
                                          ObjectAccessPolicy accessPolicy,
                                          ConfirmationTokenService tokenService,
                                          ReportOutputSanitizer outputSanitizer) {
        this.draftRepository = draftRepository;
        this.auditService = auditService;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.tokenService = tokenService;
        this.outputSanitizer = outputSanitizer;
    }

    /** Saves human text edits while keeping every evidence link and section shape immutable. */
    @Transactional
    public EditResult edit(Long draftId, String token, LlmReportOutput editedContent) {
        ReportDraft draft = resolveDraft(draftId, token, ObjectAction.CONFIRM);
        if (draft.status() != ReportDraftStatus.DRAFTED || draft.content() == null
                || editedContent == null || !sameEvidenceShape(draft.content(), editedContent)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "人工修改只能调整报告文字，不能改变证据引用、指标或章节结构。");
        }
        LlmReportOutput sanitized = outputSanitizer.sanitize(editedContent);
        CurrentActor actor = actorProvider.currentActor();
        if (!draftRepository.updateContent(
                draftId, ReportDraftStatus.DRAFTED, sanitized, actor.actorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new ReportAuditLog(
                draft.requestId(), draftId, "DRAFT_EDITED", 0, null, null,
                ReportDraftStatus.DRAFTED.name(), null, null,
                draft.ownerActorId(), actor.actorId(), null, null,
                null, null, null, "report-human-edit-v1", null, null, null, null));
        return new EditResult(draftId, ReportDraftStatus.DRAFTED, sanitized);
    }

    @Transactional
    public ConfirmationResult confirm(Long draftId, String token) {
        ReportDraft draft = resolveDraft(draftId, token, ObjectAction.CONFIRM);
        if (draft.status() != ReportDraftStatus.DRAFTED) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        CurrentActor actor = actorProvider.currentActor();
        if (!draftRepository.transitionStatus(
                draftId, ReportDraftStatus.DRAFTED, ReportDraftStatus.CONFIRMED, actor.actorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new ReportAuditLog(
                draft.requestId(), draftId, "CONFIRMED", 0, null, null,
                ReportDraftStatus.CONFIRMED.name(), null, null,
                draft.ownerActorId(), actor.actorId(), null, null,
                null, null, null, null, null, null, null, null));
        return new ConfirmationResult(draftId, ReportDraftStatus.CONFIRMED);
    }

    @Transactional
    public ConfirmationResult cancel(Long draftId, String token) {
        ReportDraft draft = resolveDraft(draftId, token, ObjectAction.CANCEL);
        if (draft.status() != ReportDraftStatus.DRAFTED
                && draft.status() != ReportDraftStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        CurrentActor actor = actorProvider.currentActor();
        if (!draftRepository.transitionStatus(
                draftId, draft.status(), ReportDraftStatus.CANCELED, actor.actorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new ReportAuditLog(
                draft.requestId(), draftId, "CANCELED", 0, null, null,
                ReportDraftStatus.CANCELED.name(), null, null,
                draft.ownerActorId(), actor.actorId(), null, null,
                null, null, null, null, null, null, null, null));
        return new ConfirmationResult(draftId, ReportDraftStatus.CANCELED);
    }

    /** Reopens an owned record with a newly rotated digest-backed token. */
    @Transactional
    public ReviewSession openReviewSession(Long draftId) {
        ReportDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CurrentActor actor = actorProvider.currentActor();
        if (!accessPolicy.allowed(actor, ObjectAction.CONFIRM, draft.ownerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (draft.status() != ReportDraftStatus.DRAFTED
                && draft.status() != ReportDraftStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        Instant expiresAt = Instant.now().plus(REVIEW_SESSION_TTL);
        if (!draftRepository.replaceConfirmationToken(
                draftId, draft.status(), token.digest(), actor.actorId(), expiresAt)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new ReportAuditLog(
                draft.requestId(), draftId, "REVIEW_SESSION_OPENED", 0, null, null,
                draft.status().name(), null, null, draft.ownerActorId(), actor.actorId(),
                null, null, null, null, null, null, null, null, null, null));
        return new ReviewSession(draftId, draft.status(), draft.content(), draft.reviewReasons(),
                token.rawToken(), expiresAt);
    }

    private ReportDraft resolveDraft(Long draftId, String rawToken, ObjectAction action) {
        ReportDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CurrentActor actor = actorProvider.currentActor();
        if (!accessPolicy.allowed(actor, action, draft.ownerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!tokenService.matches(rawToken, draft.tokenDigest())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (draft.expiresAt() == null || !draft.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        return draft;
    }

    private boolean sameEvidenceShape(LlmReportOutput original, LlmReportOutput edited) {
        if (!original.executiveSummarySourceIds().equals(edited.executiveSummarySourceIds())
                || original.metricHighlights().size() != edited.metricHighlights().size()
                || original.completedItems().size() != edited.completedItems().size()
                || original.risks().size() != edited.risks().size()
                || original.actionItems().size() != edited.actionItems().size()
                || original.suggestions().size() != edited.suggestions().size()
                || original.citations().size() != edited.citations().size()) {
            return false;
        }
        for (int i = 0; i < original.metricHighlights().size(); i++) {
            var before = original.metricHighlights().get(i);
            var after = edited.metricHighlights().get(i);
            if (!Objects.equals(before.metricName(), after.metricName())
                    || !Objects.equals(before.metricValue(), after.metricValue())
                    || !Objects.equals(before.unit(), after.unit())
                    || !before.sourceIds().equals(after.sourceIds())) return false;
        }
        for (int i = 0; i < original.completedItems().size(); i++) {
            if (!original.completedItems().get(i).sourceIds().equals(
                    edited.completedItems().get(i).sourceIds())) return false;
        }
        for (int i = 0; i < original.risks().size(); i++) {
            if (!original.risks().get(i).sourceIds().equals(edited.risks().get(i).sourceIds())) return false;
        }
        for (int i = 0; i < original.actionItems().size(); i++) {
            var before = original.actionItems().get(i); var after = edited.actionItems().get(i);
            if (before.origin() != after.origin() || !before.sourceIds().equals(after.sourceIds())) return false;
        }
        for (int i = 0; i < original.suggestions().size(); i++) {
            var before = original.suggestions().get(i); var after = edited.suggestions().get(i);
            if (before.origin() != after.origin() || !before.sourceIds().equals(after.sourceIds())) return false;
        }
        for (int i = 0; i < original.citations().size(); i++) {
            if (!original.citations().get(i).sourceId().equals(edited.citations().get(i).sourceId())) return false;
        }
        return true;
    }

    public record ConfirmationResult(Long draftId, ReportDraftStatus status) {
    }

    public record EditResult(Long draftId, ReportDraftStatus status, LlmReportOutput content) {
    }

    public record ReviewSession(Long draftId, ReportDraftStatus status, LlmReportOutput content,
                                String reviewReasons, String confirmationToken,
                                Instant expiresAt) { }
}
