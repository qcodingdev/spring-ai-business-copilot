package dev.qcoding.businesscopilot.resumecopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;

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
        Instant now = Instant.now();
        if (!command.expiresAt().isAfter(command.grantedAt())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "候选人授权有效期必须晚于授权时间");
        }
        if (command.grantedAt().isAfter(now.plusSeconds(300))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "候选人授权时间不能晚于当前时间");
        }
        if (!command.expiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "候选人授权在记录时必须仍然有效");
        }
        if (command.purpose() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "必须选择候选人授权用途");
        }
        String actorId = actorProvider.currentActor().actorId();
        try {
            return jdbcTemplate.queryForObject("""
                INSERT INTO hr_candidate_consents (
                    consent_reference, candidate_reference, purpose, purpose_code,
                    granted_at, expires_at, recorded_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, consent_reference, candidate_reference, purpose_code,
                          granted_at, expires_at, revoked_at, recorded_by
                """, this::mapConsent, command.consentReference().trim(),
                    command.candidateReference().trim(), command.purpose().name(),
                    command.purpose().name(), Timestamp.from(command.grantedAt()),
                    Timestamp.from(command.expiresAt()), actorId);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "授权凭据编号已存在；授权内容不可覆盖，请使用新的凭据编号");
        }
    }

    public Consent revokeConsent(String reference) {
        CurrentActor actor = actorProvider.currentActor();
        List<Consent> rows = jdbcTemplate.query("""
                UPDATE hr_candidate_consents
                SET revoked_at = now()
                WHERE consent_reference = ? AND revoked_at IS NULL
                  AND (recorded_by = ? OR ?)
                RETURNING id, consent_reference, candidate_reference, purpose_code,
                          granted_at, expires_at, revoked_at, recorded_by
                """, this::mapConsent, reference, actor.actorId(), isAdmin(actor));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    public List<Consent> consents() {
        CurrentActor actor = actorProvider.currentActor();
        return jdbcTemplate.query("""
                SELECT id, consent_reference, candidate_reference, purpose_code,
                       granted_at, expires_at, revoked_at, recorded_by
                FROM hr_candidate_consents
                WHERE recorded_by = ? OR ?
                ORDER BY created_at DESC
                """, this::mapConsent, actor.actorId(), isAdmin(actor));
    }

    @Transactional
    public ResumeAssessmentService.AssessmentResponse assessAuthorized(
            long jobId, String candidateReference, String consentReference, String resumeText) {
        Consent consent = requireValidConsent(
                consentReference, candidateReference, ConsentPurpose.ASSESSMENT);
        ResumeAssessmentService.AssessmentResponse response =
                assessmentService.assess(jobId, resumeText);
        jdbcTemplate.update("""
                UPDATE resume_submissions
                SET consent_id = ?, candidate_reference = ?
                WHERE id = ?
                """, consent.id(), candidateReference.trim(), response.submissionId());
        return response;
    }

    @Transactional
    public ResumeAssessmentService.AssessmentResponse assessAuthorizedFile(
            long jobId, String candidateReference, String consentReference,
            String fileName, String contentType, byte[] content) {
        Consent consent = requireValidConsent(
                consentReference, candidateReference, ConsentPurpose.ASSESSMENT);
        ResumeAssessmentService.AssessmentResponse response =
                assessmentService.assessFile(jobId, fileName, contentType, content);
        jdbcTemplate.update("""
                UPDATE resume_submissions
                SET consent_id = ?, candidate_reference = ?
                WHERE id = ?
                """, consent.id(), candidateReference.trim(), response.submissionId());
        return response;
    }

    @Transactional
    public InterviewQuestion saveQuestion(QuestionCommand command) {
        validateNeutralText(command.questionText());
        validateNeutralText(command.evidenceGuidance());
        String actorId = actorProvider.currentActor().actorId();
        String questionKey = command.questionKey().trim();
        advisoryLock("hr-question:" + questionKey);
        Long version = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM hr_interview_question_bank WHERE question_key = ?
                """, Long.class, questionKey);
        return jdbcTemplate.queryForObject("""
                INSERT INTO hr_interview_question_bank (
                    question_key, version, category, question_text,
                    evidence_guidance, prohibited_topics, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id, question_key, version, category, question_text,
                          evidence_guidance, active, owner_actor_id, approved_by, updated_at
                """, this::mapQuestion, questionKey, version,
                command.category().trim(), sensitiveTextMasker.mask(command.questionText().trim()),
                sensitiveTextMasker.mask(command.evidenceGuidance().trim()),
                json(command.prohibitedTopics() == null ? List.of() : command.prohibitedTopics()),
                actorId);
    }

    @Transactional
    public InterviewQuestion approveQuestion(long id) {
        String actorId = actorProvider.currentActor().actorId();
        List<ApprovalTarget> targets = jdbcTemplate.query("""
                SELECT question_key AS object_key, owner_actor_id
                FROM hr_interview_question_bank WHERE id = ?
                """, (rs, rowNum) -> new ApprovalTarget(
                rs.getString("object_key"), rs.getString("owner_actor_id")), id);
        if (targets.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        ApprovalTarget target = targets.getFirst();
        if (actorId.equals(target.ownerActorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "题库版本必须由不同于创建者的管理员批准");
        }
        advisoryLock("hr-question:" + target.objectKey());
        jdbcTemplate.update("""
                UPDATE hr_interview_question_bank
                SET active = FALSE, updated_at = now()
                WHERE question_key = ? AND active = TRUE AND id <> ?
                """, target.objectKey(), id);
        List<InterviewQuestion> rows = jdbcTemplate.query("""
                UPDATE hr_interview_question_bank
                SET active = TRUE, approved_by = ?, approved_at = now(), updated_at = now()
                WHERE id = ? AND active = FALSE
                RETURNING id, question_key, version, category, question_text,
                          evidence_guidance, active, owner_actor_id, approved_by, updated_at
                """, this::mapQuestion, actorId, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return rows.getFirst();
    }

    public List<InterviewQuestion> questions() {
        return jdbcTemplate.query("""
                SELECT id, question_key, version, category, question_text,
                       evidence_guidance, active, owner_actor_id, approved_by, updated_at
                FROM hr_interview_question_bank
                ORDER BY category, question_key, version DESC
                """, this::mapQuestion);
    }

    @Transactional
    public InterviewSession openSession(long assessmentId, List<String> interviewerActorIds) {
        CurrentActor actor = actorProvider.currentActor();
        String actorId = actor.actorId();
        List<SessionRow> sessions = jdbcTemplate.query("""
                INSERT INTO hr_interview_sessions (
                    assessment_id, session_reference, owner_actor_id
                )
                SELECT ?, ?, ?
                WHERE EXISTS (
                    SELECT 1 FROM resume_assessments
                    WHERE id = ? AND status = 'REVIEWED'
                      AND (owner_actor_id = ? OR ?)
                )
                RETURNING id, assessment_id, session_reference, status,
                          owner_actor_id, created_at, closed_at
                """, this::mapSession,
                assessmentId, "interview-" + UUID.randomUUID(),
                actorId, assessmentId, actorId, isAdmin(actor));
        if (sessions.isEmpty()) {
            Integer visible = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM resume_assessments
                    WHERE id = ? AND (owner_actor_id = ? OR ?)
                    """, Integer.class, assessmentId, actorId, isAdmin(actor));
            if (visible != null && visible > 0) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "候选人评估必须完成人工复核后才能创建面试协作");
            }
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        InterviewSession session = sessions.getFirst().session();
        Set<String> members = new LinkedHashSet<>();
        members.add(actorId);
        if (interviewerActorIds != null) {
            interviewerActorIds.stream().map(String::trim).filter(value -> !value.isBlank())
                    .limit(20).forEach(members::add);
        }
        for (String member : members) {
            jdbcTemplate.update("""
                    INSERT INTO hr_interview_session_members(
                        session_id, actor_id, member_role, added_by
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, session.id(), member,
                    member.equals(actorId) ? "OWNER" : "INTERVIEWER", actorId);
        }
        return session;
    }

    public List<InterviewSession> sessions() {
        CurrentActor actor = actorProvider.currentActor();
        return jdbcTemplate.query("""
                SELECT DISTINCT s.id, s.assessment_id, s.session_reference, s.status,
                       s.owner_actor_id, s.created_at, s.closed_at
                FROM hr_interview_sessions s
                LEFT JOIN hr_interview_session_members m ON m.session_id = s.id
                WHERE m.actor_id = ? OR ?
                ORDER BY s.created_at DESC
                """, (rs, rowNum) -> mapSession(rs, rowNum).session(),
                actor.actorId(), isAdmin(actor));
    }

    public InterviewMember addSessionMember(long sessionId, String memberActorId) {
        CurrentActor actor = actorProvider.currentActor();
        if (memberActorId == null || memberActorId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        List<InterviewMember> rows = jdbcTemplate.query("""
                INSERT INTO hr_interview_session_members(
                    session_id, actor_id, member_role, added_by
                )
                SELECT ?, ?, 'INTERVIEWER', ?
                WHERE EXISTS (
                    SELECT 1 FROM hr_interview_sessions
                    WHERE id = ? AND status = 'OPEN'
                      AND (owner_actor_id = ? OR ?)
                )
                ON CONFLICT (session_id, actor_id) DO UPDATE SET actor_id = EXCLUDED.actor_id
                RETURNING session_id, actor_id, member_role, added_by, created_at
                """, this::mapMember, sessionId, memberActorId.trim(), actor.actorId(),
                sessionId, actor.actorId(), isAdmin(actor));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    public InterviewSession closeSession(long sessionId) {
        CurrentActor actor = actorProvider.currentActor();
        List<InterviewSession> rows = jdbcTemplate.query("""
                UPDATE hr_interview_sessions
                SET status = 'CLOSED', closed_at = now()
                WHERE id = ? AND status = 'OPEN'
                  AND (owner_actor_id = ? OR ?)
                RETURNING id, assessment_id, session_reference, status,
                          owner_actor_id, created_at, closed_at
                """, (rs, rowNum) -> mapSession(rs, rowNum).session(),
                sessionId, actor.actorId(), isAdmin(actor));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    public InterviewOpinion saveOpinion(long sessionId, OpinionCommand command) {
        validateNeutralText(command.opinion());
        if (command.evidence() == null || command.evidence().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "面试意见必须关联可核验的证据");
        }
        String actorId = actorProvider.currentActor().actorId();
        List<InterviewOpinion> rows = jdbcTemplate.query("""
                INSERT INTO hr_interview_opinions (
                    session_id, interviewer_actor_id, evidence_json, gaps_json, opinion_text
                )
                SELECT ?, ?, ?::jsonb, ?::jsonb, ?
                WHERE EXISTS (
                    SELECT 1 FROM hr_interview_sessions s
                    JOIN hr_interview_session_members m ON m.session_id = s.id
                    WHERE s.id = ? AND s.status = 'OPEN' AND m.actor_id = ?
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
                sensitiveTextMasker.mask(command.opinion().trim()), sessionId, actorId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    public InterviewSummary interviewSummary(long sessionId) {
        requireSessionMember(sessionId);
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

    public List<InterviewMember> sessionMembers(long sessionId) {
        requireSessionMember(sessionId);
        return jdbcTemplate.query("""
                SELECT session_id, actor_id, member_role, added_by, created_at
                FROM hr_interview_session_members
                WHERE session_id = ? ORDER BY created_at
                """, this::mapMember, sessionId);
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

    public List<AtsConnection> atsConnections() {
        return jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider,
                       base_url, secret_ref, enabled, owner_actor_id
                FROM hr_ats_connections
                ORDER BY display_name
                """, this::mapAtsConnection);
    }

    public List<AtsImport> atsImports() {
        return jdbcTemplate.query("""
                SELECT i.id, i.connection_id, i.external_candidate_id,
                       i.consent_reference, i.source_updated_at, i.imported_by, i.imported_at
                FROM hr_ats_imports i
                JOIN hr_candidate_consents c
                  ON c.consent_reference = i.consent_reference
                WHERE c.recorded_by = ? OR ?
                ORDER BY i.imported_at DESC
                LIMIT 200
                """, this::mapAtsImport, actorProvider.currentActor().actorId(),
                isAdmin(actorProvider.currentActor()));
    }

    public AtsImportResult importAts(long connectionId, String consentReference, int limit) {
        AtsConnection connection = requireAtsConnection(connectionId);
        if (!connection.enabled()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        Consent consent = requireValidConsent(
                consentReference, null, ConsentPurpose.ATS_IMPORT);
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

    @Transactional
    public OnboardingChecklist saveChecklist(ChecklistCommand command) {
        validateNeutralText(command.title());
        if (command.items() == null || command.items().isEmpty()
                || command.items().stream().anyMatch(item -> item == null
                    || item.itemKey() == null || item.itemKey().isBlank()
                    || item.title() == null || item.title().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "入职清单必须包含带稳定标识和标题的办理事项");
        }
        long distinctKeys = command.items().stream().map(ChecklistItem::itemKey)
                .map(String::trim).distinct().count();
        if (distinctKeys != command.items().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "入职清单事项标识不能重复");
        }
        if (command.items().stream().noneMatch(ChecklistItem::required)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "入职清单必须至少包含一个必办事项");
        }
        if (command.items().stream().anyMatch(
                item -> item.dueInDays() < 1 || item.dueInDays() > 365)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "入职清单事项期限必须为 1 到 365 天");
        }
        String actorId = actorProvider.currentActor().actorId();
        String checklistKey = command.checklistKey().trim();
        advisoryLock("hr-onboarding:" + checklistKey);
        Long version = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM hr_onboarding_checklists WHERE checklist_key = ?
                """, Long.class, checklistKey);
        return jdbcTemplate.queryForObject("""
                INSERT INTO hr_onboarding_checklists (
                    checklist_key, version, title, role_scope, items_json,
                    knowledge_references, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                RETURNING id, checklist_key, version, title, role_scope,
                          items_json::text, knowledge_references::text,
                          active, owner_actor_id, approved_by, updated_at
                """, this::mapChecklist, checklistKey, version,
                command.title().trim(), trimToNull(command.roleScope()),
                json(command.items()), json(command.knowledgeReferences() == null
                        ? List.of() : command.knowledgeReferences()), actorId);
    }

    @Transactional
    public OnboardingChecklist approveChecklist(long id) {
        String actorId = actorProvider.currentActor().actorId();
        List<ApprovalTarget> targets = jdbcTemplate.query("""
                SELECT checklist_key AS object_key, owner_actor_id
                FROM hr_onboarding_checklists WHERE id = ?
                """, (rs, rowNum) -> new ApprovalTarget(
                rs.getString("object_key"), rs.getString("owner_actor_id")), id);
        if (targets.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        ApprovalTarget target = targets.getFirst();
        if (actorId.equals(target.ownerActorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "入职清单版本必须由不同于创建者的管理员批准");
        }
        advisoryLock("hr-onboarding:" + target.objectKey());
        jdbcTemplate.update("""
                UPDATE hr_onboarding_checklists
                SET active = FALSE, updated_at = now()
                WHERE checklist_key = ? AND active = TRUE AND id <> ?
                """, target.objectKey(), id);
        List<OnboardingChecklist> rows = jdbcTemplate.query("""
                UPDATE hr_onboarding_checklists
                SET active = TRUE, approved_by = ?, approved_at = now(), updated_at = now()
                WHERE id = ? AND active = FALSE
                RETURNING id, checklist_key, version, title, role_scope,
                          items_json::text, knowledge_references::text,
                          active, owner_actor_id, approved_by, updated_at
                """, this::mapChecklist, actorId, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return rows.getFirst();
    }

    public List<OnboardingChecklist> checklists() {
        return jdbcTemplate.query("""
                SELECT id, checklist_key, version, title, role_scope,
                       items_json::text, knowledge_references::text,
                       active, owner_actor_id, approved_by, updated_at
                FROM hr_onboarding_checklists
                ORDER BY checklist_key, version DESC
                """, this::mapChecklist);
    }

    @Transactional
    public OnboardingInstance startOnboarding(long checklistId, String employeeReference) {
        String actorId = actorProvider.currentActor().actorId();
        List<OnboardingChecklist> checklists = jdbcTemplate.query("""
                SELECT id, checklist_key, version, title, role_scope,
                       items_json::text, knowledge_references::text,
                       active, owner_actor_id, approved_by, updated_at
                FROM hr_onboarding_checklists
                WHERE id = ? AND active = TRUE AND approved_by IS NOT NULL
                """, this::mapChecklist, checklistId);
        if (checklists.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        OnboardingChecklist checklist = checklists.getFirst();
        List<OnboardingInstance> instances;
        try {
            instances = jdbcTemplate.query("""
                    INSERT INTO hr_onboarding_instances(
                        checklist_id, employee_reference, owner_actor_id
                    ) VALUES (?, ?, ?)
                    RETURNING id, checklist_id, employee_reference, status,
                              owner_actor_id, created_at, completed_at, canceled_at
                    """, this::mapOnboardingInstance,
                    checklistId, employeeReference.trim(), actorId);
        } catch (DataAccessException ex) {
            if (!isUniqueConstraint(ex)) throw ex;
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该员工已存在此清单的办理实例");
        }
        OnboardingInstance instance = instances.getFirst();
        for (ChecklistItem item : checklist.items()) {
            jdbcTemplate.update("""
                    INSERT INTO hr_onboarding_tasks(
                        instance_id, item_key, title, guidance, required, owner_role, due_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, instance.id(), item.itemKey().trim(), item.title().trim(),
                    trimToNull(item.guidance()), item.required(), trimToNull(item.ownerRole()),
                    Timestamp.from(instance.createdAt().plus(Duration.ofDays(item.dueInDays()))));
        }
        return instanceWithTasks(instance);
    }

    public List<OnboardingInstance> onboardingInstances() {
        CurrentActor actor = actorProvider.currentActor();
        List<OnboardingInstance> instances = jdbcTemplate.query("""
                SELECT id, checklist_id, employee_reference, status,
                       owner_actor_id, created_at, completed_at, canceled_at
                FROM hr_onboarding_instances
                WHERE owner_actor_id = ? OR ?
                ORDER BY created_at DESC
                """, this::mapOnboardingInstance, actor.actorId(), isAdmin(actor));
        return instances.stream().map(this::instanceWithTasks).toList();
    }

    @Transactional
    public OnboardingInstance completeOnboardingTask(
            long instanceId, long taskId, String evidenceReference) {
        if (evidenceReference == null || evidenceReference.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "完成入职事项必须提供证据引用");
        }
        CurrentActor actor = actorProvider.currentActor();
        int updated = jdbcTemplate.update("""
                UPDATE hr_onboarding_tasks t
                SET status = 'COMPLETED', evidence_reference = ?,
                    completed_by = ?, completed_at = now()
                FROM hr_onboarding_instances i
                WHERE t.id = ? AND t.instance_id = ? AND t.status = 'PENDING'
                  AND i.id = t.instance_id AND i.status IN ('IN_PROGRESS', 'COMPLETED')
                  AND (i.owner_actor_id = ? OR ?)
                """, evidenceReference.trim(), actor.actorId(), taskId, instanceId,
                actor.actorId(), isAdmin(actor));
        if (updated != 1) throw new BusinessException(ErrorCode.NOT_FOUND);
        jdbcTemplate.update("""
                UPDATE hr_onboarding_instances i
                SET status = 'COMPLETED', completed_at = now()
                WHERE i.id = ? AND i.status = 'IN_PROGRESS'
                  AND NOT EXISTS (
                    SELECT 1 FROM hr_onboarding_tasks t
                    WHERE t.instance_id = i.id AND t.required = TRUE
                      AND t.status <> 'COMPLETED'
                  )
                """, instanceId);
        return requireOnboardingInstance(instanceId);
    }

    public OnboardingInstance cancelOnboarding(long instanceId) {
        CurrentActor actor = actorProvider.currentActor();
        List<OnboardingInstance> rows = jdbcTemplate.query("""
                UPDATE hr_onboarding_instances
                SET status = 'CANCELED', canceled_at = now()
                WHERE id = ? AND status = 'IN_PROGRESS'
                  AND (owner_actor_id = ? OR ?)
                RETURNING id, checklist_id, employee_reference, status,
                          owner_actor_id, created_at, completed_at, canceled_at
                """, this::mapOnboardingInstance, instanceId,
                actor.actorId(), isAdmin(actor));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return instanceWithTasks(rows.getFirst());
    }

    private Consent requireValidConsent(String reference, String candidateReference,
                                        ConsentPurpose purpose) {
        List<Consent> rows = jdbcTemplate.query("""
                SELECT id, consent_reference, candidate_reference, purpose_code,
                       granted_at, expires_at, revoked_at, recorded_by
                FROM hr_candidate_consents
                WHERE consent_reference = ? AND purpose_code = ?
                  AND revoked_at IS NULL AND granted_at <= now() AND expires_at > now()
                  AND (?::text IS NULL OR candidate_reference = ?::text)
                """, this::mapConsent, reference, purpose.name(),
                candidateReference, candidateReference);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "候选人授权不存在、已撤回或已过期");
        }
        return rows.getFirst();
    }

    private Consent mapConsent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Consent(rs.getLong("id"), rs.getString("consent_reference"),
                rs.getString("candidate_reference"),
                ConsentPurpose.valueOf(rs.getString("purpose_code")),
                rs.getTimestamp("granted_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                instant(rs.getTimestamp("revoked_at")), rs.getString("recorded_by"));
    }

    private InterviewQuestion mapQuestion(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new InterviewQuestion(rs.getLong("id"), rs.getString("question_key"),
                rs.getLong("version"), rs.getString("category"), rs.getString("question_text"),
                rs.getString("evidence_guidance"), rs.getBoolean("active"),
                rs.getString("owner_actor_id"), rs.getString("approved_by"),
                rs.getTimestamp("updated_at").toInstant());
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

    private AtsImport mapAtsImport(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new AtsImport(rs.getLong("id"), rs.getLong("connection_id"),
                rs.getString("external_candidate_id"), rs.getString("consent_reference"),
                instant(rs.getTimestamp("source_updated_at")), rs.getString("imported_by"),
                rs.getTimestamp("imported_at").toInstant());
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
                    rs.getBoolean("active"), rs.getString("owner_actor_id"),
                    rs.getString("approved_by"),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (JacksonException ex) {
            throw new IllegalStateException("入职清单读取失败", ex);
        }
    }

    private SessionRow mapSession(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SessionRow(new InterviewSession(
                rs.getLong("id"), rs.getLong("assessment_id"),
                rs.getString("session_reference"), rs.getString("status"),
                rs.getString("owner_actor_id"), rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("closed_at"))));
    }

    private InterviewMember mapMember(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new InterviewMember(rs.getLong("session_id"), rs.getString("actor_id"),
                rs.getString("member_role"), rs.getString("added_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private void requireSessionMember(long sessionId) {
        CurrentActor actor = actorProvider.currentActor();
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM hr_interview_sessions s
                LEFT JOIN hr_interview_session_members m ON m.session_id = s.id
                WHERE s.id = ? AND (m.actor_id = ? OR ?)
                """, Integer.class, sessionId, actor.actorId(), isAdmin(actor));
        if (count == null || count == 0) throw new BusinessException(ErrorCode.NOT_FOUND);
    }

    private OnboardingInstance mapOnboardingInstance(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new OnboardingInstance(rs.getLong("id"), rs.getLong("checklist_id"),
                rs.getString("employee_reference"), rs.getString("status"),
                rs.getString("owner_actor_id"), rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("canceled_at")),
                List.of(), List.of());
    }

    private OnboardingInstance instanceWithTasks(OnboardingInstance instance) {
        List<OnboardingTask> tasks = jdbcTemplate.query("""
                SELECT id, instance_id, item_key, title, guidance, required, owner_role,
                       status, evidence_reference, completed_by, completed_at, due_at
                FROM hr_onboarding_tasks WHERE instance_id = ? ORDER BY id
                """, (rs, rowNum) -> new OnboardingTask(
                rs.getLong("id"), rs.getLong("instance_id"), rs.getString("item_key"),
                rs.getString("title"), rs.getString("guidance"), rs.getBoolean("required"),
                rs.getString("owner_role"), rs.getString("status"),
                rs.getString("evidence_reference"), rs.getString("completed_by"),
                instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("due_at"))), instance.id());
        List<String> knowledgeReferences = jdbcTemplate.queryForObject("""
                SELECT knowledge_references::text FROM hr_onboarding_checklists WHERE id = ?
                """, (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString(1),
                        new TypeReference<List<String>>() { });
            } catch (JacksonException ex) {
                throw new IllegalStateException("入职知识引用读取失败", ex);
            }
        }, instance.checklistId());
        return new OnboardingInstance(instance.id(), instance.checklistId(),
                instance.employeeReference(), instance.status(), instance.ownerActorId(),
                instance.createdAt(), instance.completedAt(), instance.canceledAt(),
                knowledgeReferences == null ? List.of() : knowledgeReferences, tasks);
    }

    private OnboardingInstance requireOnboardingInstance(long instanceId) {
        CurrentActor actor = actorProvider.currentActor();
        List<OnboardingInstance> rows = jdbcTemplate.query("""
                SELECT id, checklist_id, employee_reference, status,
                       owner_actor_id, created_at, completed_at, canceled_at
                FROM hr_onboarding_instances
                WHERE id = ? AND (owner_actor_id = ? OR ?)
                """, this::mapOnboardingInstance, instanceId,
                actor.actorId(), isAdmin(actor));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return instanceWithTasks(rows.getFirst());
    }

    private void advisoryLock(String key) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtext(?))::text", String.class, key);
    }

    private boolean isAdmin(CurrentActor actor) {
        return actor != null && actor.hasRole(BusinessRole.ADMIN);
    }

    private boolean isUniqueConstraint(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        return cause instanceof java.sql.SQLException sqlException
                && "23505".equals(sqlException.getSQLState());
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
    public enum ConsentPurpose { ASSESSMENT, ATS_IMPORT }
    public record ConsentCommand(String consentReference, String candidateReference,
                                 ConsentPurpose purpose,
                                 Instant grantedAt, Instant expiresAt) { }
    public record Consent(long id, String consentReference, String candidateReference,
                          ConsentPurpose purpose,
                          Instant grantedAt, Instant expiresAt, Instant revokedAt, String recordedBy) { }
    public record QuestionCommand(String questionKey, String category, String questionText,
                                  String evidenceGuidance, List<String> prohibitedTopics) { }
    public record InterviewQuestion(long id, String questionKey, long version, String category,
                                    String questionText, String evidenceGuidance, boolean active,
                                    String ownerActorId, String approvedBy, Instant updatedAt) { }
    public record InterviewSession(long id, long assessmentId, String sessionReference,
                                   String status, String ownerActorId, Instant createdAt,
                                   Instant closedAt) { }
    public record InterviewMember(long sessionId, String actorId, String memberRole,
                                  String addedBy, Instant createdAt) { }
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
    public record AtsImport(long id, long connectionId, String externalCandidateId,
                            String consentReference, Instant sourceUpdatedAt,
                            String importedBy, Instant importedAt) { }
    public record ChecklistItem(String itemKey, String title, String guidance,
                                boolean required, String ownerRole, int dueInDays) {
        public ChecklistItem(String itemKey, String title, String guidance,
                             boolean required, String ownerRole) {
            this(itemKey, title, guidance, required, ownerRole, 1);
        }

        public ChecklistItem {
            if (dueInDays == 0) {
                dueInDays = 1;
            }
        }
    }
    public record ChecklistCommand(String checklistKey, String title, String roleScope,
                                   List<ChecklistItem> items, List<String> knowledgeReferences) { }
    public record OnboardingChecklist(long id, String checklistKey, long version, String title,
                                      String roleScope, List<ChecklistItem> items,
                                      List<String> knowledgeReferences, boolean active,
                                      String ownerActorId, String approvedBy, Instant updatedAt) { }
    public record OnboardingInstance(long id, long checklistId, String employeeReference,
                                     String status, String ownerActorId, Instant createdAt,
                                     Instant completedAt, Instant canceledAt,
                                     List<String> knowledgeReferences,
                                     List<OnboardingTask> tasks) { }
    public record OnboardingTask(long id, long instanceId, String itemKey, String title,
                                 String guidance, boolean required, String ownerRole,
                                 String status, String evidenceReference,
                                 String completedBy, Instant completedAt, Instant dueAt) { }
    private record ApprovalTarget(String objectKey, String ownerActorId) { }
    private record SessionRow(InterviewSession session) { }
}
