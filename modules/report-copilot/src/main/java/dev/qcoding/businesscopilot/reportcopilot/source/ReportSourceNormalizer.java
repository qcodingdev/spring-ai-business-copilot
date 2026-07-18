package dev.qcoding.businesscopilot.reportcopilot.source;

import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;

/** 对报告证据脱敏、限长、计算摘要，并分配请求范围内的来源 ID。 */
public class ReportSourceNormalizer {

    private final SensitiveTextMasker sensitiveTextMasker;
    private final ReportCopilotProperties properties;
    private final Clock clock;

    public ReportSourceNormalizer(SensitiveTextMasker sensitiveTextMasker, ReportCopilotProperties properties) {
        this(sensitiveTextMasker, properties, Clock.systemUTC());
    }

    public ReportSourceNormalizer(SensitiveTextMasker sensitiveTextMasker, ReportCopilotProperties properties,
                                  Clock clock) {
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.properties = properties;
        this.clock = clock;
    }

    public List<ReportSource> normalize(List<RawReportSource> rawSources) {
        List<RawReportSource> sources = rawSources == null ? List.of() : rawSources;
        if (sources.size() > properties.maxSourceCount()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源数量超过配置限制。");
        }
        return sources.stream().map(this::normalizeOne).toList();
    }

    private ReportSource normalizeOne(RawReportSource rawSource) {
        if (rawSource == null || rawSource.sourceType() == null || rawSource.title() == null
                || rawSource.title().isBlank() || rawSource.content() == null || rawSource.content().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "报告来源内容不完整。");
        }
        String sanitizedContent = sensitiveTextMasker.mask(rawSource.content().trim());
        if (sanitizedContent.length() > properties.maxSourceLength()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源内容超过配置长度限制。");
        }
        String title = sensitiveTextMasker.mask(rawSource.title().trim());
        if (title.length() > properties.maxSourceLength()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源标题超过配置长度限制。");
        }
        var sanitizedAttributes = sanitizeAttributes(rawSource.attributes());
        String providerId = boundedMetadata(rawSource.providerId(), "client", "providerId");
        String sourceVersion = boundedMetadata(rawSource.sourceVersion(), "1", "sourceVersion");
        Instant observedAt = rawSource.observedAt() == null ? Instant.EPOCH : rawSource.observedAt();
        String sourceTimezone = validTimezone(rawSource.sourceTimezone());
        String sourceUnit = boundedMetadata(rawSource.sourceUnit(), "", "sourceUnit");
        Instant validUntil = resolveValidUntil(observedAt, rawSource.validUntil());
        SourceFreshness freshness = freshness(observedAt, validUntil);
        String normalized = rawSource.sourceType() + "\n" + title + "\n" + sanitizedContent
                + "\n" + stableAttributes(sanitizedAttributes)
                + "\nproviderId=" + providerId
                + "\nsourceVersion=" + sourceVersion
                + "\nobservedAt=" + observedAt
                + "\nsourceTimezone=" + sourceTimezone
                + "\nsourceUnit=" + sourceUnit
                + "\nvalidUntil=" + validUntil;
        UUID snapshotId = UUID.randomUUID();
        return new ReportSource(snapshotId.toString(), snapshotId, rawSource.sourceType(), title,
                sanitizedContent, sha256(normalized), sanitizedAttributes,
                providerId, sourceVersion, observedAt, sourceTimezone, sourceUnit,
                validUntil, freshness);
    }

    private java.util.Map<String, String> sanitizeAttributes(java.util.Map<String, String> attributes) {
        var sanitized = new LinkedHashMap<String, String>();
        attributes.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .forEach(entry -> sanitized.put(sensitiveTextMasker.mask(entry.getKey()),
                        sensitiveTextMasker.mask(entry.getValue())));
        return java.util.Map.copyOf(sanitized);
    }

    private String boundedMetadata(String value, String fallback, String field) {
        String normalized = value == null || value.isBlank() ? fallback : sensitiveTextMasker.mask(value.trim());
        if (normalized.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源字段 " + field + " 超过 100 个字符。");
        }
        return normalized;
    }

    private String validTimezone(String value) {
        String normalized = boundedMetadata(value, "UTC", "sourceTimezone");
        try {
            ZoneId.of(normalized);
            return normalized;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源时区无效。");
        }
    }

    private Instant resolveValidUntil(Instant observedAt, Instant requestedValidUntil) {
        if (requestedValidUntil != null) {
            if (!observedAt.equals(Instant.EPOCH) && requestedValidUntil.isBefore(observedAt)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "报告来源 validUntil 不能早于 observedAt。");
            }
            return requestedValidUntil;
        }
        return observedAt.equals(Instant.EPOCH) ? null : observedAt.plus(properties.sourceFreshnessTtl());
    }

    private SourceFreshness freshness(Instant observedAt, Instant validUntil) {
        if (observedAt.equals(Instant.EPOCH) || validUntil == null) {
            return SourceFreshness.UNKNOWN;
        }
        return clock.instant().isAfter(validUntil) ? SourceFreshness.STALE : SourceFreshness.FRESH;
    }

    private String stableAttributes(java.util.Map<String, String> attributes) {
        return attributes.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }
}
