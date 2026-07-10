package dev.qcoding.businesscopilot.reportcopilot.source;

import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Sanitizes, bounds, hashes, and assigns request-scoped IDs to report evidence. */
public class ReportSourceNormalizer {

    private final SensitiveTextMasker sensitiveTextMasker;
    private final ReportCopilotProperties properties;

    public ReportSourceNormalizer(SensitiveTextMasker sensitiveTextMasker, ReportCopilotProperties properties) {
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.properties = properties;
    }

    public List<ReportSource> normalize(List<RawReportSource> rawSources) {
        List<RawReportSource> sources = rawSources == null ? List.of() : rawSources;
        if (sources.size() > properties.maxSourceCount()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The report contains more sources than the configured limit.");
        }
        return sources.stream().map(this::normalizeOne).toList();
    }

    private ReportSource normalizeOne(RawReportSource rawSource) {
        if (rawSource == null || rawSource.sourceType() == null || rawSource.title() == null
                || rawSource.title().isBlank() || rawSource.content() == null || rawSource.content().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A report source is incomplete.");
        }
        String sanitizedContent = sensitiveTextMasker.mask(rawSource.content().trim());
        if (sanitizedContent.length() > properties.maxSourceLength()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A report source exceeds the configured length limit.");
        }
        String title = sensitiveTextMasker.mask(rawSource.title().trim());
        String normalized = rawSource.sourceType() + "\n" + title + "\n" + sanitizedContent;
        return new ReportSource(UUID.randomUUID().toString(), rawSource.sourceType(), title,
                sanitizedContent, sha256(normalized), rawSource.attributes());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
