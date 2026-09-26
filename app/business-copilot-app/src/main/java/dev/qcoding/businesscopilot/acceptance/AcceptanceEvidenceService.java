package dev.qcoding.businesscopilot.acceptance;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.readiness.EnterpriseReadiness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 分层验收证据服务（CORE-05）。
 *
 * <p>运行就绪证据由 EnterpriseReadiness 评估同步写入；模型质量、供应商验收和
 * 发布门禁证据由对应流程或管理员显式记录。每类证据独立判定，
 * {@code READY} 不替代其他验收：发布判定要求四类全部 PASS。</p>
 */
public class AcceptanceEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceEvidenceService.class);

    /** 类别聚合的优先级：FAILED > NOT_VERIFIED > ATTENTION > PASS。 */
    private static final Map<AcceptanceEvidence.Status, Integer> SEVERITY = Map.of(
            AcceptanceEvidence.Status.FAILED, 3,
            AcceptanceEvidence.Status.NOT_VERIFIED, 2,
            AcceptanceEvidence.Status.ATTENTION, 1,
            AcceptanceEvidence.Status.PASS, 0);

    private static final RowMapper<AcceptanceEvidence.Evidence> MAPPER = (rs, rowNum) ->
            new AcceptanceEvidence.Evidence(
                    rs.getLong("id"),
                    AcceptanceEvidence.Category.valueOf(rs.getString("category")),
                    rs.getString("name"),
                    AcceptanceEvidence.Status.valueOf(rs.getString("status")),
                    rs.getString("source"),
                    rs.getString("applicable_version"),
                    rs.getString("note"),
                    rs.getString("recorded_by"),
                    rs.getTimestamp("recorded_at") != null
                            ? rs.getTimestamp("recorded_at").toInstant() : null);

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActorProvider actorProvider;
    private final String currentVersion;

    public AcceptanceEvidenceService(JdbcTemplate jdbcTemplate, CurrentActorProvider actorProvider,
                                      String currentVersion) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorProvider = actorProvider;
        this.currentVersion = currentVersion;
    }

    public String currentVersion() { return currentVersion; }

    /** 管理员登记外部验收证据；运行就绪只能由服务端实时评估生成。 */
    public AcceptanceEvidence.Evidence record(AcceptanceEvidence.Evidence evidence) {
        if (evidence.category() == AcceptanceEvidence.Category.RUNTIME_READINESS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "运行就绪证据必须来自服务端检查");
        }
        return append(evidence);
    }

    public AcceptanceEvidence.Evidence recordRuntimeAssessment(EnterpriseReadiness.Assessment assessment) {
        AcceptanceEvidence.Status status = switch (assessment.status()) {
            case READY -> AcceptanceEvidence.Status.PASS;
            case ATTENTION -> AcceptanceEvidence.Status.ATTENTION;
            case BLOCKED -> AcceptanceEvidence.Status.FAILED;
            case NOT_CONFIGURED -> AcceptanceEvidence.Status.NOT_VERIFIED;
        };
        return append(new AcceptanceEvidence.Evidence(null, AcceptanceEvidence.Category.RUNTIME_READINESS,
                "enterprise-readiness", status, "EnterpriseReadinessService:" + assessment.contentHash(),
                assessment.applicationVersion(), "通过 %d 项 / 警告 %d 项 / 阻断 %d 项".formatted(
                        assessment.passedCount(), assessment.warningCount(), assessment.blockerCount()), null, null));
    }

    /** 历史只追加；身份、时间由服务端产生，不采信请求中的记录人和时间。 */
    private AcceptanceEvidence.Evidence append(AcceptanceEvidence.Evidence evidence) {
        CurrentActor actor = actorProvider == null ? null : actorProvider.currentActor();
        if (actor == null || !actor.authenticated() || !actor.hasRole(BusinessRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (evidence.category() == null || evidence.status() == null
                || evidence.applicableVersion() == null || evidence.applicableVersion().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        AcceptanceEvidence.Evidence saved = jdbcTemplate.query("""
                    INSERT INTO acceptance_evidence (category, name, status, source,
                                                     applicable_version, note, recorded_by, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING *
                    """,
                    MAPPER,
                    evidence.category().name(), evidence.name(), evidence.status().name(),
                    evidence.source(), evidence.applicableVersion(), evidence.note(),
                    actor.actorId(), Timestamp.from(Instant.now())).getFirst();
        log.info("验收证据记录：category={}，name={}，status={}",
                evidence.category(), evidence.name(), evidence.status());
        return saved;
    }

    /** 某类别的全部证据。 */
    public List<AcceptanceEvidence.Evidence> byCategory(AcceptanceEvidence.Category category) {
        return jdbcTemplate.query(
                "SELECT * FROM acceptance_evidence WHERE category = ? AND applicable_version = ? ORDER BY recorded_at DESC, id DESC LIMIT 100",
                MAPPER, category.name(), currentVersion);
    }

    /** 每类别的聚合状态；无证据的类别是 NOT_VERIFIED（未验证不算通过）。 */
    public List<AcceptanceEvidence.CategorySummary> categorySummaries() {
        Map<AcceptanceEvidence.Category, List<AcceptanceEvidence.Evidence>> grouped =
                new EnumMap<>(AcceptanceEvidence.Category.class);
        List<AcceptanceEvidence.Evidence> latest = jdbcTemplate.query("""
                SELECT DISTINCT ON (category, name) * FROM acceptance_evidence
                WHERE applicable_version = ? ORDER BY category, name, recorded_at DESC, id DESC
                """, MAPPER, currentVersion);
        for (AcceptanceEvidence.Evidence evidence : latest) {
            grouped.computeIfAbsent(evidence.category(), ignored -> new java.util.ArrayList<>()).add(evidence);
        }
        return java.util.Arrays.stream(AcceptanceEvidence.Category.values())
                .map(category -> summarize(category, grouped.get(category)))
                .toList();
    }

    /** 发布视角判定：四类全部 PASS 才可发布；运行就绪 READY 不替代其他类别。 */
    public AcceptanceEvidence.ReleaseReadiness releaseReadiness() {
        List<AcceptanceEvidence.CategorySummary> summaries = categorySummaries();
        boolean releasable = summaries.stream()
                .allMatch(s -> s.status() == AcceptanceEvidence.Status.PASS);
        String blockingReason = releasable ? null : summaries.stream()
                .filter(s -> s.status() != AcceptanceEvidence.Status.PASS)
                .map(s -> s.category() + " " + describe(s.status()))
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
        return new AcceptanceEvidence.ReleaseReadiness(
                releasable,
                summaries.stream().map(AcceptanceEvidence.CategorySummary::status)
                        .max(Comparator.comparingInt(SEVERITY::get)).orElse(AcceptanceEvidence.Status.NOT_VERIFIED),
                summaries, blockingReason);
    }

    private AcceptanceEvidence.CategorySummary summarize(AcceptanceEvidence.Category category,
                                                         List<AcceptanceEvidence.Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return new AcceptanceEvidence.CategorySummary(category,
                    AcceptanceEvidence.Status.NOT_VERIFIED, 0,
                    "暂无证据：未验证，不能记为通过",
                    "No evidence recorded: not verified, cannot count as passed");
        }
        AcceptanceEvidence.Status worst = evidence.stream()
                .map(AcceptanceEvidence.Evidence::status)
                .max(Comparator.comparingInt(SEVERITY::get))
                .orElse(AcceptanceEvidence.Status.NOT_VERIFIED);
        return new AcceptanceEvidence.CategorySummary(category, worst, evidence.size(),
                "最新证据状态：" + describe(worst) + "（" + evidence.size() + " 条）",
                "Latest evidence status: " + worst.name() + " (" + evidence.size() + " items)");
    }

    private static String describe(AcceptanceEvidence.Status status) {
        return switch (status) {
            case PASS -> "通过";
            case ATTENTION -> "需关注";
            case FAILED -> "未通过";
            case NOT_VERIFIED -> "未验证";
        };
    }
}
