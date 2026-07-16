package dev.qcoding.businesscopilot.datacopilot.confirmation;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/** Creates and atomically consumes database-backed SQL candidates. */
public class SqlConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(SqlConfirmationService.class);

    private final SqlCandidateStore store;
    private final DataCopilotConfirmationProperties properties;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final ConfirmationTokenService tokenService;

    public SqlConfirmationService(SqlCandidateStore store,
                                  DataCopilotConfirmationProperties properties,
                                  CurrentActorProvider actorProvider,
                                  ObjectAccessPolicy accessPolicy,
                                  ConfirmationTokenService tokenService) {
        this.store = store;
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.tokenService = tokenService;
    }

    public SqlCandidate createExecutableCandidate(String sql) {
        return createExecutableCandidate(sql, null, null, null);
    }

    public SqlCandidate createExecutableCandidate(String sql, String requestId,
                                                   String userQuestion, String modelName) {
        return createExecutableCandidate(sql, requestId, modelName, null, null, null);
    }

    public SqlCandidate createExecutableCandidate(String sql, String requestId, String modelName,
                                                   PromptTemplateMetadata promptMetadata,
                                                   AiInvocationMetadata aiMetadata,
                                                   String policyVersion) {
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        Instant now = Instant.now();
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        SqlCandidate candidate = new SqlCandidate(
                UUID.randomUUID().toString(),
                sql,
                token.rawToken(),
                token.digest(),
                SqlCandidateStatus.PENDING,
                actor.actorId(),
                requestId,
                aiMetadata != null ? aiMetadata.modelName() : modelName,
                promptMetadata != null ? promptMetadata.name() : "data-copilot/sql-generation.st",
                promptMetadata != null ? promptMetadata.version() : "v1",
                promptMetadata != null ? promptMetadata.contentHash() : null,
                aiMetadata,
                policyVersion,
                now,
                now.plusSeconds(properties.candidateTtlMinutes() * 60L),
                null,
                null);
        store.save(candidate);
        log.info("Created executable SQL candidate: id={}, owner={}, expiresAt={}",
                candidate.candidateId(), actor.actorId(), candidate.expiresAt());
        return candidate;
    }

    /** Guardrail-rejected candidates are returned to the caller but are not persisted. */
    public SqlCandidate createNotExecutableCandidate(String sql) {
        Instant now = Instant.now();
        return new SqlCandidate(
                UUID.randomUUID().toString(), sql, null, null, SqlCandidateStatus.REJECTED,
                actorProvider.currentActor().actorId(), null, null, null, null, null,
                null, null,
                now, null, null, null);
    }

    public SqlCandidate confirmAndConsume(String candidateId, String confirmationToken) {
        Instant now = Instant.now();
        CurrentActor actor = actorProvider.currentActor();
        SqlCandidate candidate = store.findById(candidateId);
        if (candidate == null || !accessPolicy.allowed(
                actor, ObjectAction.EXECUTE, candidate.ownerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (candidate.status() != SqlCandidateStatus.PENDING) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        if (!tokenService.matches(confirmationToken, candidate.tokenDigest())) {
            throw new SqlCandidateNotExecutableException();
        }
        if (candidate.isExpired(now)) {
            store.expire(candidateId, now);
            throw new SqlCandidateExpiredException();
        }
        if (!store.consume(candidateId, actor.actorId(), now)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        log.info("Confirmed SQL candidate: id={}, actor={}", candidateId, actor.actorId());
        return new SqlCandidate(
                candidate.candidateId(), candidate.sql(), null, null, SqlCandidateStatus.CONSUMED,
                candidate.ownerActorId(), candidate.requestId(), candidate.modelName(),
                candidate.promptName(), candidate.promptVersion(), candidate.promptHash(),
                candidate.aiMetadata(), candidate.policyVersion(),
                candidate.createdAt(), candidate.expiresAt(), now, actor.actorId());
    }

    public int evictExpired() {
        return store.evictExpired(Instant.now());
    }
}
