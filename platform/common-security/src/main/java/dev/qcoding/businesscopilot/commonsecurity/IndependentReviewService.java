package dev.qcoding.businesscopilot.commonsecurity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared four-eyes review boundary used by business objects that require an independent approver.
 * Implementations own persistence and must enforce creator/reviewer separation server-side.
 */
public interface IndependentReviewService {

    enum SubjectType {
        DATA_SQL_CANDIDATE,
        REPORT_DRAFT
    }

    enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        SUPERSEDED
    }

    enum Decision {
        APPROVE,
        REJECT
    }

    record ReviewTask(Long id, SubjectType subjectType, String subjectId,
                      String ownerActorId, Status status, String reviewerActorId,
                      String reviewNote, long contentVersion, Instant submittedAt,
                      Instant reviewedAt, Instant updatedAt, Map<String, Object> subject) {
    }

    /** Register a new independently reviewed business object. Admin-owned objects may be auto-approved. */
    ReviewTask register(SubjectType subjectType, String subjectId, String ownerActorId);

    /** Reset an approved/rejected review when the protected content changes. */
    ReviewTask contentChanged(SubjectType subjectType, String subjectId, String ownerActorId);

    /** Close the review lifecycle when its protected business object is canceled. */
    ReviewTask supersede(SubjectType subjectType, String subjectId, String ownerActorId);

    ReviewTask status(SubjectType subjectType, String subjectId);

    /** Fail closed unless the current content version has an approved review. */
    void requireApproved(SubjectType subjectType, String subjectId, String ownerActorId);

    List<ReviewTask> queue(SubjectType subjectType);

    List<ReviewTask> mine(SubjectType subjectType);

    ReviewTask decide(long reviewTaskId, Decision decision, String note);
}
