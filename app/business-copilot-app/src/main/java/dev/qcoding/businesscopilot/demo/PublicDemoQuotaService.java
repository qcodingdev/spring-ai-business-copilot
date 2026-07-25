package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/** 使用数据库条件更新实现跨实例一致的公网每日额度。 */
@Service
public class PublicDemoQuotaService {

    static final String GLOBAL_FINGERPRINT = "__GLOBAL__";
    private final JdbcTemplate jdbcTemplate;
    private final PublicDemoProperties properties;

    public PublicDemoQuotaService(JdbcTemplate jdbcTemplate, PublicDemoProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Transactional
    public UsageSnapshot consumeBusinessOperation(String fingerprint) {
        LocalDate date = today();
        ensureRow(date, fingerprint);
        int changed = jdbcTemplate.update("""
                UPDATE public_demo_usage_daily
                SET ai_operations = ai_operations + 1, updated_at = ?
                WHERE usage_date = ? AND client_fingerprint = ? AND ai_operations < ?
                """, Timestamp.from(Instant.now()), Date.valueOf(date), fingerprint,
                properties.clientDailyOperations());
        if (changed != 1) {
            throw new BusinessException(ErrorCode.PUBLIC_DEMO_LIMIT_REACHED,
                    "今日体验额度已用尽，可查看预生成示例结果。额度将在 " + resetAt() + " 重置。");
        }
        return snapshot(fingerprint);
    }

    @Transactional
    public void reserveExternalModelCall() {
        LocalDate date = today();
        ensureRow(date, GLOBAL_FINGERPRINT);
        int changed = jdbcTemplate.update("""
                UPDATE public_demo_usage_daily
                SET external_model_calls = external_model_calls + 1, updated_at = ?
                WHERE usage_date = ? AND client_fingerprint = ? AND external_model_calls < ?
                """, Timestamp.from(Instant.now()), Date.valueOf(date), GLOBAL_FINGERPRINT,
                properties.globalDailyModelCalls());
        if (changed != 1) {
            throw new BusinessException(ErrorCode.PUBLIC_DEMO_LIMIT_REACHED,
                    "全站今日模型额度已用尽，可查看预生成示例结果。额度将在 " + resetAt() + " 重置。");
        }
    }

    public UsageSnapshot snapshot(String fingerprint) {
        LocalDate date = today();
        ensureRow(date, fingerprint);
        Integer used = jdbcTemplate.queryForObject("""
                SELECT ai_operations FROM public_demo_usage_daily
                WHERE usage_date = ? AND client_fingerprint = ?
                """, Integer.class, Date.valueOf(date), fingerprint);
        int usedValue = used == null ? 0 : used;
        return new UsageSnapshot(
                usedValue,
                Math.max(0, properties.clientDailyOperations() - usedValue),
                properties.clientDailyOperations(),
                resetAt());
    }

    private void ensureRow(LocalDate date, String fingerprint) {
        jdbcTemplate.update("""
                INSERT INTO public_demo_usage_daily (
                    usage_date, client_fingerprint, ai_operations, external_model_calls, updated_at
                ) VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (usage_date, client_fingerprint) DO NOTHING
                """, Date.valueOf(date), fingerprint, Timestamp.from(Instant.now()));
    }

    private LocalDate today() {
        return LocalDate.now(properties.zoneId());
    }

    private String resetAt() {
        ZonedDateTime next = today().plusDays(1).atStartOfDay(properties.zoneId());
        return next.toOffsetDateTime().toString();
    }

    public record UsageSnapshot(int used, int remaining, int dailyLimit, String resetsAt) {
    }
}
