package dev.qcoding.businesscopilot.resumecopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** HR 企业协作：授权、题库、面试证据、ATS 只读导入和入职清单。 */
public class HrEnterpriseService {

    private static final Pattern FORBIDDEN_DECISION = Pattern.compile(
            "(排名|打分|总分|录用|淘汰|筛退|通过率|绩效预测|离职预测|推荐候选人)");

    private final JdbcTemplate jdbcTemplate;
    private final ResumeAssessmentService assessmentService;
    private final CurrentActorProvider actorProvider;
    private final ExternalSecretResolver secretResolver;
    private final SensitiveTextMasker sensitiveTextMasker;
    private final ObjectMapper objectMapper;
    private final ExternalEndpointPolicy endpointPolicy;
    private final ExternalHttpClientFactory clientFactory;

    public HrEnterpriseService(
            JdbcTemplate jdbcTemplate,
            ResumeAssessmentService assessmentService,
            CurrentActorProvider actorProvider,
            ExternalSecretResolver secretResolver,
            SensitiveTextMasker sensitiveTextMasker,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy,
            ExternalHttpClientFactory clientFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.assessmentService = assessmentService;
        this.actorProvider = actorProvider;
        this.secretResolver = secretResolver;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
        this.clientFactory = clientFactory;
    }

    public Consent saveConsent(ConsentCommand command) {
        if (!command.expiresAt().isAfter(command.grantedAt())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "候选人授权有效期必须晚于授权时间");
        }
        String actorId = actorProvider.currentActor().actorId();
        return jdbcTemplate.queryForObject("""
                INSERT INTO hr_candidate_consents (
                    consent_reference, candidate_reference, purpose,
                    granted_at, expires_at, recorded_by
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (consent_reference) DO UPDATE SET
                    candidate_reference = EXCLUDED.candidate_reference,
                    purpose = EXCLUDED.purpose,
                    granted_at = EXCLUDED.granted_at,
                    expires_at = EXCLUDED.expires_at,
                    revoked_at = NULL,
                    recorded_by = EXCLUDED.recorded_by
                RETURNING id, consent_reference, candidate_reference, purpose,
                          granted_at, expires_at, revoked_at, recorded_by
                """, this::mapConsent, command.consentReference().trim(),
                command.candidateReference().trim(), command.purpose().trim(),
                Timestamp.from(command.grantedAt()), Timestamp.from(command.expiresAt()), actorId);
    }

    public Consent revokeConsent(String reference) {
        List<Consent> rows = jdbcTemplate.query("""
                UPDATE hr_candidate_consents
                SET revoked_at = now()
                WHERE consent_reference = ? AND revoked_at IS NULL
                RETURNING id, consent_reference, candidate_reference, purpose,
                          granted_at, expires_at, revoked_at, recorded_by
                """, this::mapConsent, reference);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    @Transactional
    public ResumeAssessmentService.AssessmentResponse assessAuthorized(
            long jobId, String candidateReference, String consentReference, String resumeText) {
        Consent consent = requireValidConsent(consentReference, candidateReference);
        ResumeAssessmentService.AssessmentResponse response =
                assessmentService.assess(jobId, resumeText);
        jdbcTemplate.update("""
                UPDATE resume_submissions
                SET consent_id = ?, candidate_reference = ?
                WHERE id = ?
                """, consent.id(), candidateReference.trim(), response.submissionId());
        return response;
    }

    public InterviewQuestion saveQuestion(QuestionCommand command) {
        validateNeutralText(command.questionText());
        validateNeutralText(command.evidenceGuidance());
        String actorId = actorProvider.currentActor().actorId();
        Long version = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM hr_interview_question_bank WHERE question_key = ?
                """, Long.class, command.questionKey().trim());
        return jdbcTemplate.queryForObject("""
                INSERT INTO hr_interview_question_bank (
                    question_key, version, category, question_text,
                    evidence_guidance, prohibited_topics, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id, question_key, version, category, question_text,
                          evidence_guidance, active, approved_by, updated_at
                """, this::mapQuestion, command.questionKey().trim(), version,
                command.category().trim(), sensitiveTextMasker.mask(command.questionText().trim()),
                sensitiveTextMasker.mask(command.evidenceGuidance().trim()),
                json(command.prohibitedTopics() == null ? List.of() : command.prohibitedTopics()),
                actorId);
    }

    public InterviewQuestion approveQuestion(long id) {
        String actorId = actorProvider.currentActor().actorId();
        List<InterviewQuestion> rows = jdbcTemplate.query("""
                UPDATE hr_interview_question_bank
                SET active = TRUE, approved_by = ?, approved_at = now(), updated_at = now()
                WHERE id = ?
                RETURNING id, question_key, version, category, question_text,
                          evidence_guidance, active, approved_by, updated_at
                """, this::mapQuestion, actorId, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    public List<InterviewQuestion> questions() {
        return jdbcTemplate.query("""
                SELECT id, question_key, version, category, question_text,
                       evidence_guidance, active, approved_by, updated_at
                FROM hr_interview_question_bank
                ORDER BY category, question_key, version DESC
                """, this::mapQuestion);
    }

    public InterviewSession openSession(long assessmentId) {
        String actorId = actorProvider.currentActor().actorId();
        List<Long> ids = jdbcTemplate.query("""
                INSERT INTO hr_interview_sessions (
                    assessment_id, session_reference, owner_actor_id
                )
                SELECT ?, ?, ?
                WHERE EXISTS (
                    SELECT 1 FROM resume_assessments
                    WHERE id = ? AND owner_actor_id = ?
                )
                RETURNING id
                """, (rs, rowNum) -> rs.getLong("id"),
                assessmentId, "interview-" + UUID.randomUUID(),
                actorId, assessmentId, actorId);
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return new InterviewSession(ids.getFirst(), assessmentId, "OPEN");
    }

    public InterviewOpinion saveOpinion(long sessionId, OpinionCommand command) {
        validateNeutralText(command.opinion());
        if (command.evidence() == null || command.evidence().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "面试意见必须关联可核验的证据");
        }
        String actorId = actorProvider.currentActor().actorId();
        return jdbcTemplate.queryForObject("""
                INSERT INTO hr_interview_opinions (
                    session_id, interviewer_actor_id, evidence_json, gaps_json, opinion_text
                )
                SELECT ?, ?, ?::jsonb, ?::jsonb, ?
                WHERE EXISTS (
                    SELECT 1 FROM hr_interview_sessions
                    WHERE id = ? AND status = 'OPEN'
                )
                ON CONFLICT (session_id, interviewer_actor_id) DO UPDATE SET
                    evidence_json = EXCLUDED.evidence_json,
                    gaps_json = EXCLUDED.gaps_json,
                    opinion_text = EXCLUDED.opinion_text,
                    updated_at = now()
                RETURNING id, session_id, interviewer_actor_id,
                          evidence_json::text, gaps_json::text, opinion_text, updated_at
                """, this::mapOpinion, sessionId, actorId, json(command.evidence()),
                json(command.gaps() == null ? List.of() : command.gaps()),
                sensitiveTextMasker.mask(command.opinion().trim()), sessionId);
    }

    public InterviewSummary interviewSummary(long sessionId) {
        List<InterviewOpinion> opinions = jdbcTemplate.query("""
                SELECT id, session_id, interviewer_actor_id,
                       evidence_json::text, gaps_json::text, opinion_text, updated_at
                FROM hr_interview_opinions
                WHERE session_id = ?
                ORDER BY updated_at
                """, this::mapOpinion, sessionId);
        List<String> gaps = opinions.stream().flatMap(opinion -> opinion.gaps().stream())
                .distinct().toList();
        return new InterviewSummary(sessionId, opinions.size(), opinions, gaps,
                "仅汇总面试证据与待核实项，不形成排名、评分或录用决定");
    }

    public AtsConnection saveAtsConnection(AtsConnectionCommand command) {
        ExternalSecretResolver.validateRef(command.secretRef());
        endpointPolicy.validateBaseUrl(command.baseUrl());
        String actorId = actorProvider.currentActor().actorId();
        return jdbcTemplate.queryForObject("""
                INSERT INTO hr_ats_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (connection_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    secret_ref = EXCLUDED.secret_ref,
                    enabled = EXCLUDED.enabled,
                    owner_actor_id = EXCLUDED.owner_actor_id,
                    updated_at = now()
                RETURNING id, connection_key, display_name, provider,
                          base_url, secret_ref, enabled, owner_actor_id
                """, this::mapAtsConnection, command.connectionKey().trim(),
                command.displayName().trim(), command.provider().name(),
                command.baseUrl().trim(), command.secretRef().trim(),
                command.enabled(), actorId);
    }

    public AtsImportResult importAts(long connectionId, String consentReference, int limit) {
        AtsConnection connection = requireAtsConnection(connectionId);
        if (!connection.enabled()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        Consent consent = requireValidConsent(consentReference, null);
        String secret = secretResolver.resolve(connection.secretRef());
        String auth = secret.contains(" ") ? secret : "Bearer " + secret;
        RestClient client = clientFactory.builder(connection.baseUrl())
                .defaultHeader("Authorization", auth).build();
        JsonNode response = clientFactory.validatePayload(client.get()
                .uri(atsUrl(connection, limit)).retrieve().body(JsonNode.class));
        JsonNode candidates = firstArray(response, "candidates", "results", "items", "data");
        int imported = 0;
        for (JsonNode candidate : iterable(candidates)) {
            String externalId = firstText(candidate, "id", "candidate_id", "application_id");
            if (externalId == null
                    || !externalId.equals(consent.candidateReference())) {
                continue;
            }
            String sanitized = sensitiveTextMasker.mask(json(candidate));
            jdbcTemplate.update("""
                    INSERT INTO hr_ats_imports (
                        connection_id, external_candidate_id, consent_reference,
                        sanitized_payload, source_updated_at, imported_by
                    ) VALUES (?, ?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (connection_id, external_candidate_id) DO UPDATE SET
                        consent_reference = EXCLUDED.consent_reference,
                        sanitized_payload = EXCLUDED.sanitized_payload,
                        source_updated_at = EXCLUDED.source_updated_at,
                        imported_by = EXCLUDED.imported_by,
                        imported_at = now()
                    """, connectionId, externalId, consent.consentReference(), sanitized,
                    timestamp(parseInstant(firstText(candidate, "updated_at", "updatedAt"))),
                    actorProvider.currentActor().actorId());
            imported++;
            if (imported >= limit) break;
        }
        return new AtsImportResult(imported, "READ_ONLY");
    }

    public OnboardingChecklist saveChecklist(ChecklistCommand command) {
        validateNeutralText(command.title());
        String actorId = actorProvider.currentActor().actorId();
        Long version = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM hr_onboarding_checklists WHERE checklist_key = ?
                """, Long.class, command.checklistKey().trim());
        return jdbcTemplate.queryForObject("""
                INSERT INTO hr_onboarding_checklists (
                    checklist_key, version, title, role_scope, items_json,
                    knowledge_references, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                RETURNING id, checklist_key, version, title, role_scope,
                          items_json::text, knowledge_references::text,
                          active, approved_by, updated_at
                """, this::mapChecklist, command.checklistKey().trim(), version,
                command.title().trim(), trimToNull(command.roleScope()),
                json(command.items()), json(command.knowledgeReferences() == null
                        ? List.of() : command.knowledgeReferences()), actorId);
    }

    public OnboardingChecklist approveChecklist(long id) {
        String actorId = actorProvider.currentActor().actorId();
        List<OnboardingChecklist> rows = jdbcTemplate.query("""
                UPDATE hr_onboarding_checklists
                SET active = TRUE, approved_by = ?, approved_at = now(), updated_at = now()
                WHERE id = ?
                RETURNING id, checklist_key, version, title, role_scope,
                          items_json::text, knowledge_references::text,
                          active, approved_by, updated_at
                """, this::mapChecklist, actorId, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    public List<OnboardingChecklist> checklists() {
        return jdbcTemplate.query("""
                SELECT id, checklist_key, version, title, role_scope,
                       items_json::text, knowledge_references::text,
                       active, approved_by, updated_at
                FROM hr_onboarding_checklists
                ORDER BY checklist_key, version DESC
                """, this::mapChecklist);
    }

    private Consent requireValidConsent(String reference, String candidateReference) {
        List<Consent> rows = jdbcTemplate.query("""
                SELECT id, consent_reference, candidate_reference, purpose,
                       granted_at, expires_at, revoked_at, recorded_by
                FROM hr_candidate_consents
                WHERE consent_reference = ? AND revoked_at IS NULL AND expires_at > now()
                  AND (?::text IS NULL OR candidate_reference = ?::text)
                """, this::mapConsent, reference, candidateReference, candidateReference);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "候选人授权不存在、已撤回或已过期");
        }
        return rows.getFirst();
    }

    private Consent mapConsent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Consent(rs.getLong("id"), rs.getString("consent_reference"),
                rs.getString("candidate_reference"), rs.getString("purpose"),
                rs.getTimestamp("granted_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                instant(rs.getTimestamp("revoked_at")), rs.getString("recorded_by"));
    }

    private InterviewQuestion mapQuestion(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new InterviewQuestion(rs.getLong("id"), rs.getString("question_key"),
                rs.getLong("version"), rs.getString("category"), rs.getString("question_text"),
                rs.getString("evidence_guidance"), rs.getBoolean("active"),
                rs.getString("approved_by"), rs.getTimestamp("updated_at").toInstant());
    }

    private InterviewOpinion mapOpinion(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        try {
            return new InterviewOpinion(
                    rs.getLong("id"), rs.getLong("session_id"),
                    rs.getString("interviewer_actor_id"),
                    objectMapper.readValue(rs.getString("evidence_json"),
                            new TypeReference<List<String>>() { }),
                    objectMapper.readValue(rs.getString("gaps_json"),
                            new TypeReference<List<String>>() { }),
                    rs.getString("opinion_text"), rs.getTimestamp("updated_at").toInstant());
        } catch (JacksonException ex) {
            throw new IllegalStateException("面试意见读取失败", ex);
        }
    }

    private AtsConnection requireAtsConnection(long id) {
        List<AtsConnection> rows = jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider,
                       base_url, secret_ref, enabled, owner_actor_id
                FROM hr_ats_connections WHERE id = ?
                """, this::mapAtsConnection, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private AtsConnection mapAtsConnection(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new AtsConnection(rs.getLong("id"), rs.getString("connection_key"),
                rs.getString("display_name"), AtsProvider.valueOf(rs.getString("provider")),
                rs.getString("base_url"), rs.getString("secret_ref"),
                rs.getBoolean("enabled"), rs.getString("owner_actor_id"));
    }

    private OnboardingChecklist mapChecklist(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        try {
            return new OnboardingChecklist(
                    rs.getLong("id"), rs.getString("checklist_key"), rs.getLong("version"),
                    rs.getString("title"), rs.getString("role_scope"),
                    objectMapper.readValue(rs.getString("items_json"),
                            new TypeReference<List<ChecklistItem>>() { }),
                    objectMapper.readValue(rs.getString("knowledge_references"),
                            new TypeReference<List<String>>() { }),
                    rs.getBoolean("active"), rs.getString("approved_by"),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (JacksonException ex) {
            throw new IllegalStateException("入职清单读取失败", ex);
        }
    }

    private String atsUrl(AtsConnection connection, int limit) {
        String base = trimSlash(connection.baseUrl());
        return switch (connection.provider()) {
            case WORKDAY -> base + "/ccx/api/v1/candidates?limit=" + limit;
            case GREENHOUSE -> base + "/v1/candidates?per_page=" + limit;
            case LEVER -> base + "/v1/candidates?limit=" + limit;
            case MOKA -> base + "/api/v1/candidates?page_size=" + limit;
            case BEISEN -> base + "/open-api/v1/candidates?page_size=" + limit;
            case GENERIC_READ_ONLY -> base + "/candidates?limit=" + limit;
        };
    }

    private JsonNode firstArray(JsonNode root, String... names) {
        if (root == null) return null;
        if (root.isArray()) return root;
        for (String name : names) {
            JsonNode value = root.path(name);
            if (value.isArray()) return value;
        }
        return null;
    }

    private Iterable<JsonNode> iterable(JsonNode value) {
        return value != null && value.isArray() ? value : List.of();
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isValueNode() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private void validateNeutralText(String text) {
        if (text == null || text.isBlank() || FORBIDDEN_DECISION.matcher(text).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "内容不能为空，也不能包含评分、筛退或预测性招聘决定");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("HR 企业对象序列化失败", ex);
        }
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? Timestamp.from(Instant.now()) : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? Instant.now() : Instant.parse(value);
        } catch (RuntimeException ex) {
            return Instant.now();
        }
    }

    public enum AtsProvider {
        WORKDAY, GREENHOUSE, LEVER, MOKA, BEISEN, GENERIC_READ_ONLY
    }
    public record ConsentCommand(String consentReference, String candidateReference, String purpose,
                                 Instant grantedAt, Instant expiresAt) { }
    public record Consent(long id, String consentReference, String candidateReference, String purpose,
                          Instant grantedAt, Instant expiresAt, Instant revokedAt, String recordedBy) { }
    public record QuestionCommand(String questionKey, String category, String questionText,
                                  String evidenceGuidance, List<String> prohibitedTopics) { }
    public record InterviewQuestion(long id, String questionKey, long version, String category,
                                    String questionText, String evidenceGuidance, boolean active,
                                    String approvedBy, Instant updatedAt) { }
    public record InterviewSession(long id, long assessmentId, String status) { }
    public record OpinionCommand(List<String> evidence, List<String> gaps, String opinion) { }
    public record InterviewOpinion(long id, long sessionId, String interviewerActorId,
                                   List<String> evidence, List<String> gaps, String opinion,
                                   Instant updatedAt) { }
    public record InterviewSummary(long sessionId, int interviewerCount,
                                   List<InterviewOpinion> opinions, List<String> evidenceGaps,
                                   String decisionBoundary) { }
    public record AtsConnectionCommand(String connectionKey, String displayName, AtsProvider provider,
                                       String baseUrl, String secretRef, boolean enabled) { }
    public record AtsConnection(long id, String connectionKey, String displayName, AtsProvider provider,
                                String baseUrl, String secretRef, boolean enabled,
                                String ownerActorId) { }
    public record AtsImportResult(int imported, String mode) { }
    public record ChecklistItem(String itemKey, String title, String guidance,
                                boolean required, String ownerRole) { }
    public record ChecklistCommand(String checklistKey, String title, String roleScope,
                                   List<ChecklistItem> items, List<String> knowledgeReferences) { }
    public record OnboardingChecklist(long id, String checklistKey, long version, String title,
                                      String roleScope, List<ChecklistItem> items,
                                      List<String> knowledgeReferences, boolean active,
                                      String approvedBy, Instant updatedAt) { }
}
