package dev.qcoding.businesscopilot.governance;

import dev.qcoding.businesscopilot.aicore.PromptTemplateProvider;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/** Versioned prompt governance with review, evaluation gate, rollout and rollback. */
@Service
public class PromptGovernanceService implements PromptTemplateProvider {

    private static final Logger log = LoggerFactory.getLogger(PromptGovernanceService.class);
    private static final int MAX_CONTENT_LENGTH = 50_000;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActorProvider actorProvider;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final AtomicBoolean seeded = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, CachedTemplate> activeTemplateCache = new ConcurrentHashMap<>();
    private static final long TEMPLATE_CACHE_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);

    public PromptGovernanceService(JdbcTemplate jdbcTemplate, CurrentActorProvider actorProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorProvider = actorProvider;
    }

    @Override
    public Optional<Template> activeTemplate(String location) {
        CachedTemplate cached = activeTemplateCache.get(location);
        if (cached != null && cached.expiresAtNanos() > System.nanoTime()) {
            return selectTemplate(location, cached.row());
        }
        try {
            ensureSeeded();
            List<TemplateRow> rows = jdbcTemplate.query("""
                    SELECT active.id, active.version_number, active.content, active.content_hash,
                           previous.id AS previous_id, previous.version_number AS previous_number,
                           previous.content AS previous_content, previous.content_hash AS previous_hash,
                           definition.rollout_percent
                    FROM prompt_definitions definition
                    JOIN prompt_versions active ON active.id = definition.active_version_id
                    LEFT JOIN prompt_versions previous ON previous.id = definition.previous_version_id
                    WHERE definition.prompt_key = ?
                    """, (rs, rowNum) -> new TemplateRow(
                            rs.getLong("id"), rs.getInt("version_number"), rs.getString("content"),
                            rs.getString("content_hash"), rs.getObject("previous_id", Long.class),
                            rs.getObject("previous_number", Integer.class), rs.getString("previous_content"),
                            rs.getString("previous_hash"), rs.getInt("rollout_percent")), location);
            if (rows.isEmpty()) return Optional.empty();
            TemplateRow row = rows.getFirst();
            // Cache the version pair, never a request's cohort selection.
            activeTemplateCache.put(location, new CachedTemplate(row, System.nanoTime() + TEMPLATE_CACHE_TTL_NANOS));
            return selectTemplate(location, row);
        } catch (DataAccessException ex) {
            // Isolated module tests may not install the application governance migration.
            log.warn("Prompt 治理存储不可用，继续使用包内模板：location={}", location);
            return Optional.empty();
        }
    }

    private Optional<Template> selectTemplate(String location, TemplateRow row) {
        return row.previousId() != null && bucket(location) >= row.rolloutPercent()
                ? Optional.of(new Template(row.previousContent(), "v" + row.previousNumber(), row.previousHash()))
                : Optional.of(new Template(row.content(), "v" + row.versionNumber(), row.contentHash()));
    }

    public List<DefinitionView> definitions() {
        requireAuthenticated();
        ensureSeeded();
        return jdbcTemplate.query("""
                SELECT id, prompt_key, module_key, display_name, description,
                       active_version_id, previous_version_id, rollout_percent,
                       created_at, updated_at
                FROM prompt_definitions ORDER BY module_key, prompt_key
                """, (rs, rowNum) -> new DefinitionView(
                        rs.getLong("id"), rs.getString("prompt_key"), rs.getString("module_key"),
                        rs.getString("display_name"), rs.getString("description"),
                        rs.getObject("active_version_id", Long.class),
                        rs.getObject("previous_version_id", Long.class),
                        rs.getInt("rollout_percent"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("updated_at")), versions(rs.getLong("id"))));
    }

    public List<VersionView> versions(long definitionId) {
        return jdbcTemplate.query("""
                SELECT id, definition_id, version_number, status, content, content_hash,
                       change_note, created_by, submitted_by, reviewed_by,
                       created_at, submitted_at, reviewed_at, published_at
                FROM prompt_versions WHERE definition_id = ?
                ORDER BY version_number DESC
                """, (rs, rowNum) -> new VersionView(
                        rs.getLong("id"), rs.getLong("definition_id"), rs.getInt("version_number"),
                        rs.getString("status"), rs.getString("content"), rs.getString("content_hash"),
                        rs.getString("change_note"), rs.getString("created_by"),
                        rs.getString("submitted_by"), rs.getString("reviewed_by"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("submitted_at")),
                        instant(rs.getTimestamp("reviewed_at")), instant(rs.getTimestamp("published_at"))),
                definitionId);
    }

    @Transactional
    public VersionView createVersion(String promptKey, String content, String changeNote) {
        ensureSeeded();
        return createVersion(definitionId(promptKey), content, changeNote);
    }

    @Transactional
    public VersionView createVersion(long definitionId, String content, String changeNote) {
        CurrentActor actor = requireOperatorOrAdmin();
        ensureSeeded();
        if (definitions().stream().noneMatch(item -> item.id() == definitionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        lockDefinition(definitionId);
        String normalized = validateContent(content);
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) + 1 FROM prompt_versions WHERE definition_id = ?",
                Integer.class, definitionId);
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO prompt_versions (
                    definition_id, version_number, status, content, content_hash,
                    change_note, created_by
                ) VALUES (?, ?, 'DRAFT', ?, ?, ?, ?) RETURNING id
                """, Long.class, definitionId, next, normalized, sha256(normalized),
                normalizeOptional(changeNote), actor.actorId());
        audit(definitionId, id, "CREATED", actor.actorId(), null, null, changeNote);
        return version(id);
    }

    @Transactional
    public VersionView updateDraft(long versionId, String expectedContentHash,
                                   String content, String changeNote) {
        CurrentActor actor = requireOperatorOrAdmin();
        VersionView current = version(versionId);
        requireDraftOwner(current, actor);
        if (expectedContentHash == null || !expectedContentHash.equals(current.contentHash())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "Prompt 草稿已被其他操作更新，请刷新后重试。");
        }
        String normalized = validateContent(content);
        int updated = jdbcTemplate.update("""
                UPDATE prompt_versions SET content = ?, content_hash = ?, change_note = ?
                WHERE id = ? AND status = 'DRAFT' AND content_hash = ?
                """, normalized, sha256(normalized), normalizeOptional(changeNote), versionId,
                expectedContentHash);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        audit(current.definitionId(), versionId, "UPDATED", actor.actorId(), null, null, changeNote);
        return version(versionId);
    }

    @Transactional
    public VersionView submit(long versionId) {
        CurrentActor actor = requireOperatorOrAdmin();
        VersionView current = version(versionId);
        requireDraftOwner(current, actor);
        if (current.changeNote() == null || current.changeNote().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "提交审核前必须填写变更说明。");
        }
        int updated = jdbcTemplate.update("""
                UPDATE prompt_versions
                SET status = 'IN_REVIEW', submitted_by = ?, submitted_at = now()
                WHERE id = ? AND status = 'DRAFT'
                """, actor.actorId(), versionId);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        audit(current.definitionId(), versionId, "SUBMITTED", actor.actorId(), null, null, current.changeNote());
        return version(versionId);
    }

    @Transactional
    public VersionView review(long versionId, boolean approve, String note) {
        CurrentActor actor = requireReviewerOrAdmin();
        VersionView current = version(versionId);
        if (!"IN_REVIEW".equals(current.status())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        if (!actor.hasRole(BusinessRole.ADMIN) && actor.actorId().equals(current.createdBy())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "Prompt 创建者不能审核自己的版本。");
        }
        String target = approve ? "REVIEWED" : "DRAFT";
        int updated = jdbcTemplate.update("""
                UPDATE prompt_versions
                SET status = ?, reviewed_by = ?, reviewed_at = now(),
                    change_note = CASE WHEN ? THEN change_note ELSE CONCAT(change_note, E'\nREJECTED: ', ?) END
                WHERE id = ? AND status = 'IN_REVIEW'
                """, target, actor.actorId(), approve, normalizeRequired(note), versionId);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        audit(current.definitionId(), versionId, approve ? "REVIEWED" : "REJECTED",
                actor.actorId(), null, null, note);
        return version(versionId);
    }

    @Transactional
    public DefinitionView publish(long versionId, int rolloutPercent, java.util.UUID evaluationRunId) {
        CurrentActor actor = requireAdmin();
        if (rolloutPercent < 1 || rolloutPercent > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        VersionView target = versionForUpdate(versionId);
        if (!"REVIEWED".equals(target.status())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        Integer gate = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM evaluation_runs
                WHERE id = ? AND prompt_version_id = ? AND status = 'PASSED'
                  AND gate_decision = 'ALLOW_RELEASE'
                """, Integer.class, evaluationRunId, versionId);
        if (gate == null || gate != 1) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该 Prompt 版本没有通过关联评测，不能发布。");
        }
        Long currentActive = jdbcTemplate.queryForObject(
                "SELECT active_version_id FROM prompt_definitions WHERE id = ? FOR UPDATE",
                Long.class, target.definitionId());
        jdbcTemplate.update("""
                UPDATE prompt_versions SET status = 'PUBLISHED', published_at = now()
                WHERE id = ? AND status = 'REVIEWED'
                """, versionId);
        if (currentActive != null && rolloutPercent == 100) {
            jdbcTemplate.update("UPDATE prompt_versions SET status = 'RETIRED' WHERE id = ?", currentActive);
        }
        jdbcTemplate.update("""
                UPDATE prompt_definitions
                SET previous_version_id = active_version_id, active_version_id = ?,
                    rollout_percent = ?, updated_at = now()
                WHERE id = ?
                """, versionId, rolloutPercent, target.definitionId());
        audit(target.definitionId(), versionId, "PUBLISHED", actor.actorId(), rolloutPercent,
                evaluationRunId, target.changeNote());
        activeTemplateCache.clear();
        return definition(target.definitionId());
    }

    @Transactional
    public DefinitionView rollback(long definitionId, String note) {
        CurrentActor actor = requireAdmin();
        lockDefinition(definitionId);
        DefinitionView definition = definition(definitionId);
        if (definition.previousVersionId() == null || definition.activeVersionId() == null) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "没有可回滚的上一版本。");
        }
        jdbcTemplate.update("UPDATE prompt_versions SET status = 'RETIRED' WHERE id = ?",
                definition.activeVersionId());
        jdbcTemplate.update("UPDATE prompt_versions SET status = 'PUBLISHED' WHERE id = ?",
                definition.previousVersionId());
        jdbcTemplate.update("""
                UPDATE prompt_definitions
                SET active_version_id = ?, previous_version_id = ?, rollout_percent = 100, updated_at = now()
                WHERE id = ?
                """, definition.previousVersionId(), definition.activeVersionId(), definitionId);
        audit(definitionId, definition.previousVersionId(), "ROLLED_BACK", actor.actorId(),
                100, null, normalizeRequired(note));
        activeTemplateCache.clear();
        return definition(definitionId);
    }

    public List<Map<String, Object>> audit(long definitionId) {
        requireAuthenticated();
        return jdbcTemplate.queryForList("""
                SELECT id, definition_id AS "definitionId", version_id AS "versionId",
                       action, actor_id AS "actorId", rollout_percent AS "rolloutPercent",
                       evaluation_run_id AS "evaluationRunId", note, created_at AS "createdAt"
                FROM prompt_release_audit WHERE definition_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 100
                """, definitionId);
    }

    private DefinitionView definition(long id) {
        return definitions().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private long definitionId(String promptKey) {
        List<Long> ids = jdbcTemplate.query("SELECT id FROM prompt_definitions WHERE prompt_key = ?",
                (rs, rowNum) -> rs.getLong(1), promptKey);
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return ids.getFirst();
    }

    private VersionView version(long id) {
        requireAuthenticated();
        return jdbcTemplate.query("""
                SELECT id, definition_id, version_number, status, content, content_hash,
                       change_note, created_by, submitted_by, reviewed_by,
                       created_at, submitted_at, reviewed_at, published_at
                FROM prompt_versions WHERE id = ?
                """, (rs, rowNum) -> new VersionView(
                        rs.getLong("id"), rs.getLong("definition_id"), rs.getInt("version_number"),
                        rs.getString("status"), rs.getString("content"), rs.getString("content_hash"),
                        rs.getString("change_note"), rs.getString("created_by"),
                        rs.getString("submitted_by"), rs.getString("reviewed_by"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("submitted_at")),
                        instant(rs.getTimestamp("reviewed_at")), instant(rs.getTimestamp("published_at"))), id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private VersionView versionForUpdate(long id) {
        VersionView current = version(id);
        // All releases and rollbacks take the definition lock first, so two
        // versions cannot publish concurrently or invert the rollback lock order.
        lockDefinition(current.definitionId());
        return version(id);
    }

    private void lockDefinition(long id) {
        if (jdbcTemplate.query("SELECT id FROM prompt_definitions WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), id).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private void ensureSeeded() {
        if (seeded.get()) return;
        synchronized (seeded) {
            if (seeded.get()) return;
            try {
                Resource[] resources = resolver.getResources("classpath*:prompts/**/*.st");
                for (Resource resource : resources) seed(resource);
                seeded.set(true);
            } catch (IOException ex) {
                throw new IllegalStateException("读取内置 Prompt 目录失败", ex);
            }
        }
    }

    private void seed(Resource resource) throws IOException {
        String url = resource.getURL().toString();
        int marker = url.indexOf("/prompts/");
        if (marker < 0) return;
        String promptKey = url.substring(marker + "/prompts/".length());
        int jarSuffix = promptKey.indexOf("!/");
        if (jarSuffix >= 0) promptKey = promptKey.substring(jarSuffix + 2);
        String content;
        try (var input = resource.getInputStream()) {
            content = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        String module = module(promptKey);
        long definitionId = jdbcTemplate.queryForObject("""
                INSERT INTO prompt_definitions (prompt_key, module_key, display_name, description)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (prompt_key) DO UPDATE SET prompt_key = EXCLUDED.prompt_key
                RETURNING id
                """, Long.class, promptKey, module, promptKey, "Bundled prompt template");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prompt_versions WHERE definition_id = ?", Integer.class, definitionId);
        if (count != null && count == 0) {
            long versionId = jdbcTemplate.queryForObject("""
                    INSERT INTO prompt_versions (
                        definition_id, version_number, status, content, content_hash,
                        change_note, created_by, reviewed_by, reviewed_at, published_at
                    ) VALUES (?, 1, 'PUBLISHED', ?, ?, 'Initial bundled version',
                              'system', 'system', now(), now()) RETURNING id
                    """, Long.class, definitionId, content, sha256(content));
            jdbcTemplate.update("""
                    UPDATE prompt_definitions SET active_version_id = ?, rollout_percent = 100
                    WHERE id = ?
                    """, versionId, definitionId);
        } else if (count != null && count == 1) {
            /*
             * 仅对尚未进入人工治理流程的旧系统种子追加一次新的内置版本。旧版本
             * 保留为 RETIRED，避免原地改写内容和哈希而破坏历史审计；一旦已有第二个
             * 版本便停止自动升级，避免绕过审核、评测和发布门禁覆盖用户维护的 Prompt。
             */
            String contentHash = sha256(content);
            jdbcTemplate.query("""
                    WITH candidate AS (
                        SELECT id, definition_id, content_hash
                        FROM prompt_versions
                        WHERE definition_id = ? AND version_number = 1
                          AND status = 'PUBLISHED' AND created_by = 'system'
                          AND reviewed_by = 'system'
                          AND change_note = 'Initial bundled version'
                        FOR UPDATE
                    ), new_version AS (
                        INSERT INTO prompt_versions (
                            definition_id, version_number, status, content, content_hash,
                            change_note, created_by, reviewed_by, reviewed_at, published_at
                        )
                        SELECT definition_id, 2, 'PUBLISHED', ?, ?,
                               '内置简体中文默认模板升级', 'system', 'system', now(), now()
                        FROM candidate
                        WHERE content_hash <> ?
                        ON CONFLICT (definition_id, version_number) DO NOTHING
                        RETURNING id
                    ), retired AS (
                        UPDATE prompt_versions
                        SET status = 'RETIRED'
                        WHERE id IN (SELECT id FROM candidate)
                          AND EXISTS (SELECT 1 FROM new_version)
                        RETURNING id AS previous_id
                    ), release_audit AS (
                        INSERT INTO prompt_release_audit (
                            definition_id, version_id, action, actor_id, rollout_percent, note
                        )
                        SELECT candidate.definition_id, new_version.id, 'PUBLISHED',
                               'system', 100, '内置简体中文默认模板升级'
                        FROM candidate CROSS JOIN new_version
                        RETURNING id
                    )
                    UPDATE prompt_definitions definition
                    SET previous_version_id = retired.previous_id,
                        active_version_id = new_version.id,
                        rollout_percent = 100,
                        updated_at = now()
                    FROM retired CROSS JOIN new_version CROSS JOIN release_audit
                    WHERE definition.id = ?
                    RETURNING new_version.id
                    """, (rs, rowNum) -> rs.getLong(1),
                    definitionId, content, contentHash, contentHash, definitionId);
        }
    }

    private void audit(long definitionId, Long versionId, String action, String actorId,
                       Integer rolloutPercent, java.util.UUID runId, String note) {
        jdbcTemplate.update("""
                INSERT INTO prompt_release_audit (
                    definition_id, version_id, action, actor_id,
                    rollout_percent, evaluation_run_id, note
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, definitionId, versionId, action, actorId, rolloutPercent, runId,
                normalizeOptional(note));
    }

    private int bucket(String location) {
        String key = BusinessRequestContextHolder.currentActorId() + ":"
                + BusinessRequestContextHolder.currentRequestId() + ":" + location;
        return Math.floorMod(key.hashCode(), 100);
    }

    private CurrentActor requireAuthenticated() {
        CurrentActor actor = actorProvider.currentActor();
        if (actor == null || !actor.authenticated()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return actor;
    }

    private CurrentActor requireOperatorOrAdmin() {
        CurrentActor actor = requireAuthenticated();
        if (!actor.hasRole(BusinessRole.ADMIN) && !actor.hasRole(BusinessRole.OPERATOR)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return actor;
    }

    private CurrentActor requireReviewerOrAdmin() {
        CurrentActor actor = requireAuthenticated();
        if (!actor.hasRole(BusinessRole.ADMIN) && !actor.hasRole(BusinessRole.REVIEWER)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return actor;
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = requireAuthenticated();
        if (!actor.hasRole(BusinessRole.ADMIN)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return actor;
    }

    private static void requireDraftOwner(VersionView version, CurrentActor actor) {
        if (!"DRAFT".equals(version.status())
                || (!actor.hasRole(BusinessRole.ADMIN) && !actor.actorId().equals(version.createdBy()))) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
    }

    private static String validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return content.strip();
    }

    private static String normalizeRequired(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return value.strip();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String module(String promptKey) {
        String prefix = promptKey.substring(0, promptKey.indexOf('/')).toUpperCase(Locale.ROOT);
        return switch (prefix) {
            case "DATA-COPILOT" -> "DATA";
            case "KNOWLEDGE-COPILOT" -> "KNOWLEDGE";
            case "SUPPORT-COPILOT" -> "SUPPORT";
            case "REPORT-COPILOT" -> "REPORT";
            case "RESUME-COPILOT" -> "HR";
            default -> throw new IllegalArgumentException("Unsupported prompt module: " + prefix);
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record TemplateRow(long id, int versionNumber, String content, String contentHash,
                               Long previousId, Integer previousNumber, String previousContent,
                               String previousHash, int rolloutPercent) {
    }

    private record CachedTemplate(TemplateRow row, long expiresAtNanos) {
    }

    public record DefinitionView(long id, String promptKey, String moduleKey,
                                 String displayName, String description,
                                 Long activeVersionId, Long previousVersionId,
                                 int rolloutPercent, Instant createdAt, Instant updatedAt,
                                 List<VersionView> versions) {
    }

    public record VersionView(long id, long definitionId, int versionNumber, String status,
                              String content, String contentHash, String changeNote,
                              String createdBy, String submittedBy, String reviewedBy,
                              Instant createdAt, Instant submittedAt, Instant reviewedAt,
                              Instant publishedAt) {
    }
}
