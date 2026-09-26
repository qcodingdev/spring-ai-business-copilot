package dev.qcoding.businesscopilot.datacopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationRequest;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationResponse;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DATA-04：SQL 候选的有限修正试点。
 *
 * <p>在修正预算内（默认 2 次）对既有候选重新生成：每次修正都走完整生成链
 * （澄清检查、指标口径、guardrails），产生全新候选与确认凭证；
 * 被修正的旧候选立即失效（PENDING → EXPIRED、token 摘要清空），
 * 旧凭证不可再执行，新候选必须重新展示并确认。</p>
 *
 * <p>预算以修正链的根候选计数：所有派生修正共享同一预算，
 * 超出预算拒绝继续修正并提示重新发起提问。</p>
 */
public class SqlCandidateRevisionService {

    private static final Logger log = LoggerFactory.getLogger(SqlCandidateRevisionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SqlGenerationService generationService;
    private final CurrentActorProvider actorProvider;
    private final DataEnterpriseProperties properties;

    public SqlCandidateRevisionService(JdbcTemplate jdbcTemplate,
                                       SqlGenerationService generationService,
                                       CurrentActorProvider actorProvider,
                                       DataEnterpriseProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.generationService = generationService;
        this.actorProvider = actorProvider;
        this.properties = properties;
    }

    @Transactional
    public RevisionResponse revise(RevisionCommand command) {
        if (command == null || command.candidateId() == null || command.candidateId().isBlank()
                || command.question() == null || command.question().isBlank()
                || command.instruction() == null || command.instruction().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "候选、原始问题和修正指令均不能为空");
        }
        String actorId = actorProvider.currentActor().actorId();
        boolean admin = actorProvider.currentActor().hasRole(BusinessRole.ADMIN);

        List<CandidateRow> previousRows = jdbcTemplate.query("""
                SELECT candidate_id, owner_actor_id, status
                FROM data_sql_candidates
                WHERE candidate_id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new CandidateRow(
                rs.getString("candidate_id"),
                rs.getString("owner_actor_id"),
                rs.getString("status")), command.candidateId());
        if (previousRows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        CandidateRow previous = previousRows.getFirst();
        if (!admin && !actorId.equals(previous.ownerActorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "只能修正本人的 SQL 候选");
        }
        if (!"PENDING".equals(previous.status())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "只有待确认的 SQL 候选可以修正");
        }

        // 预算按修正链根候选计数，所有派生修正共享同一预算。
        String rootCandidateId = jdbcTemplate.query(
                "SELECT root_candidate_id FROM data_candidate_revisions WHERE candidate_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, command.candidateId());
        if (rootCandidateId == null) {
            rootCandidateId = command.candidateId();
        }
        // 所有分支修正都锁住同一根候选，避免并发请求突破预算或争用修正序号。
        List<String> roots = jdbcTemplate.queryForList("""
                SELECT candidate_id FROM data_sql_candidates
                WHERE candidate_id = ? FOR UPDATE
                """, String.class, rootCandidateId);
        if (roots.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        Integer used = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_candidate_revisions WHERE root_candidate_id = ?",
                Integer.class, rootCandidateId);
        int revisionIndex = (used == null ? 0 : used) + 1;
        if (revisionIndex > properties.maxCandidateRevisions()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "候选修正预算已用尽（最多 " + properties.maxCandidateRevisions()
                            + " 次），请重新发起提问");
        }

        // 重新生成：完整走澄清检查、指标口径与 guardrails，产生全新候选与凭证。
        SqlGenerationResponse generated = generationService.generate(
                new SqlGenerationRequest(command.question(), command.instruction()));
        if (generated.clarificationQuestions() != null
                && !generated.clarificationQuestions().isEmpty()) {
            return new RevisionResponse(generated, null);
        }
        if (!generated.executable()) {
            // 新候选未通过 guardrails：不记录修正、不失效旧候选，保持现状可诊断。
            return new RevisionResponse(generated, null);
        }

        jdbcTemplate.update("""
                INSERT INTO data_candidate_revisions (
                    candidate_id, root_candidate_id, revised_from, revision_index,
                    question, instruction, actor_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, generated.candidateId(), rootCandidateId, command.candidateId(),
                revisionIndex, command.question(), command.instruction(), actorId);
        // 旧候选立即失效：PENDING → EXPIRED 且凭证摘要清空；已执行/已过期的保留历史状态。
        int invalidated = jdbcTemplate.update("""
                UPDATE data_sql_candidates
                SET status = 'EXPIRED', token_digest = NULL, updated_at = now()
                WHERE candidate_id = ? AND status = 'PENDING'
                """, command.candidateId());
        log.info("SQL 候选修正完成：revisedFrom={}，newCandidate={}，index={}，旧候选失效={}",
                command.candidateId(), generated.candidateId(), revisionIndex, invalidated > 0);

        return new RevisionResponse(generated, new RevisionInfo(
                generated.candidateId(), command.candidateId(), revisionIndex, rootCandidateId));
    }

    /** 查询候选的修正链（用于展示与审计）。 */
    public List<RevisionInfo> revisions(String rootCandidateId) {
        String actorId = actorProvider.currentActor().actorId();
        boolean admin = actorProvider.currentActor().hasRole(BusinessRole.ADMIN);
        Integer visible = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM data_sql_candidates
                WHERE candidate_id = ? AND (? OR owner_actor_id = ?)
                """, Integer.class, rootCandidateId, admin, actorId);
        if (visible == null || visible == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return jdbcTemplate.query("""
                SELECT candidate_id, revised_from, revision_index, root_candidate_id
                FROM data_candidate_revisions
                WHERE root_candidate_id = ?
                ORDER BY revision_index
                """, (rs, rowNum) -> new RevisionInfo(
                rs.getString("candidate_id"),
                rs.getString("revised_from"),
                rs.getInt("revision_index"),
                rs.getString("root_candidate_id")), rootCandidateId);
    }

    private record CandidateRow(String candidateId, String ownerActorId, String status) {
    }

    public record RevisionCommand(String candidateId, String question, String instruction) {
    }

    public record RevisionInfo(String candidateId, String revisedFrom,
                               int revisionIndex, String rootCandidateId) {
    }

    public record RevisionResponse(SqlGenerationResponse generation, RevisionInfo revision) {
    }
}
