package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.guardrails.EvidenceClaimGrounding;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 校验模型输出中的每条事实都绑定到当前请求的证据。 */
public class ReportGenerationOutputValidator {

    private final EvidenceClaimGrounding claimGrounding = new EvidenceClaimGrounding();

    /**
     * Removes model-produced metric cards that do not exactly match a METRIC source.
     * A tabular Data handoff is still valid narrative evidence, but its arbitrary
     * numeric cells must not be promoted to governed metrics. Dropping only those
     * unsafe cards lets the independently validated report continue without ever
     * exposing or persisting the invented metric representation.
     */
    public LlmReportOutput sanitizeForReview(LlmReportOutput output, List<ReportSource> sources) {
        if (output == null) {
            return output;
        }
        Set<String> validSourceIds = sources.stream().map(ReportSource::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        if (referencesUnknownSource(output, validSourceIds)) {
            return output;
        }
        String executiveSummary = output.executiveSummary();
        List<String> executiveSourceIds = output.executiveSummarySourceIds();
        if (!supportedClaim("执行摘要", executiveSummary, executiveSourceIds, sources, true)
                || executiveSummary == null || executiveSummary.isBlank()) {
            ReportSource first = sources.getFirst();
            executiveSummary = boundedExtract(first.sanitizedContent());
            executiveSourceIds = List.of(first.sourceId());
        }
        List<MetricHighlight> supported = output.metricHighlights().stream()
                .filter(highlight -> matchesMetricSource(highlight, sources))
                .toList();
        List<ReportItem> completed = output.completedItems().stream()
                .filter(item -> supportedClaim("已完成事项", item.text(), item.sourceIds(), sources, false))
                .toList();
        List<ReportItem> risks = output.risks().stream()
                .filter(item -> supportedClaim("风险项", item.text(), item.sourceIds(), sources, true))
                .toList();
        List<ReportActionItem> actions = output.actionItems().stream()
                .filter(item -> item.origin() == ReportActionItemOrigin.SOURCE_ACTION)
                .filter(item -> supportedClaim("行动项", item.text(), item.sourceIds(), sources, false))
                .toList();
        List<ReportActionItem> suggestions = output.suggestions().stream()
                .filter(item -> item.origin() == ReportActionItemOrigin.AI_SUGGESTION)
                .filter(item -> item.sourceIds().isEmpty())
                .toList();
        List<ReportCitation> citations = output.citations().stream()
                .filter(citation -> citation != null && citation.sourceId() != null
                        && validSourceIds.contains(citation.sourceId()))
                .toList();
        return new LlmReportOutput(executiveSummary, executiveSourceIds,
                supported, completed, risks, actions, suggestions, citations);
    }

    private boolean referencesUnknownSource(LlmReportOutput output, Set<String> validSourceIds) {
        java.util.stream.Stream<String> factualIds = java.util.stream.Stream.of(
                        output.executiveSummarySourceIds().stream(),
                        output.metricHighlights().stream().flatMap(item -> item.sourceIds().stream()),
                        output.completedItems().stream().flatMap(item -> item.sourceIds().stream()),
                        output.risks().stream().flatMap(item -> item.sourceIds().stream()),
                        output.actionItems().stream().flatMap(item -> item.sourceIds().stream()),
                        output.suggestions().stream().flatMap(item -> item.sourceIds().stream()))
                .flatMap(java.util.function.Function.identity());
        return factualIds.anyMatch(sourceId -> sourceId == null || !validSourceIds.contains(sourceId))
                || output.citations().stream().anyMatch(citation -> citation == null
                        || citation.sourceId() == null || !validSourceIds.contains(citation.sourceId()));
    }

    private boolean supportedClaim(String label, String text, List<String> sourceIds,
                                   List<ReportSource> sources, boolean checkNumbers) {
        List<String> violations = new ArrayList<>();
        Set<String> validSourceIds = sources.stream().map(ReportSource::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        requireSourceIds(label, text, sourceIds, validSourceIds, violations);
        if (checkNumbers) {
            requireNumericGrounding(label, text, sourceIds, sources, violations);
        }
        requireSemanticGrounding(label, text, sourceIds, sources, violations);
        return violations.isEmpty();
    }

    private String boundedExtract(String content) {
        String safe = content == null ? "" : content.strip();
        return safe.length() <= 2_000 ? safe : safe.substring(0, 2_000);
    }

    public ValidationResult validate(LlmReportOutput output, List<ReportSource> sources) {
        if (output == null) {
            return new ValidationResult(false, List.of("AI 模型未返回结构化报告。"));
        }
        Set<String> validSourceIds = sources.stream().map(ReportSource::sourceId).collect(java.util.stream.Collectors.toSet());
        List<String> violations = new ArrayList<>();
        requireSourceIds("执行摘要", output.executiveSummary(), output.executiveSummarySourceIds(), validSourceIds, violations);
        requireNumericGrounding("执行摘要", output.executiveSummary(), output.executiveSummarySourceIds(), sources, violations);
        requireSemanticGrounding("执行摘要", output.executiveSummary(), output.executiveSummarySourceIds(), sources, violations);
        output.metricHighlights().forEach(item -> validateMetricHighlight(item, sources, validSourceIds, violations));
        output.completedItems().forEach(item -> {
            requireSourceIds("已完成事项", item.text(), item.sourceIds(), validSourceIds, violations);
            requireSemanticGrounding("已完成事项", item.text(), item.sourceIds(), sources, violations);
        });
        output.risks().forEach(item -> {
            requireSourceIds("风险项", item.text(), item.sourceIds(), validSourceIds, violations);
            requireNumericGrounding("风险项", item.text(), item.sourceIds(), sources, violations);
            requireSemanticGrounding("风险项", item.text(), item.sourceIds(), sources, violations);
        });
        output.actionItems().forEach(item -> {
            if (item.origin() != ReportActionItemOrigin.SOURCE_ACTION) {
                violations.add("来源行动项必须标记为 SOURCE_ACTION。");
            }
            requireSourceIds("行动项", item.text(), item.sourceIds(), validSourceIds, violations);
            requireSemanticGrounding("行动项", item.text(), item.sourceIds(), sources, violations);
        });
        output.suggestions().forEach(item -> {
            if (item.origin() != ReportActionItemOrigin.AI_SUGGESTION) {
                violations.add("AI 建议必须标记为 AI_SUGGESTION。");
            }
            if (!item.sourceIds().isEmpty()) {
                violations.add("AI 建议不得伪装成有来源依据的事实。");
            }
        });
        output.citations().forEach(citation -> {
            if (citation == null || citation.sourceId() == null || !validSourceIds.contains(citation.sourceId())) {
                violations.add("引用指向当前请求之外的来源。");
            }
        });
        List<String> distinctViolations = violations.stream().distinct().toList();
        return new ValidationResult(distinctViolations.isEmpty(), distinctViolations);
    }

    private void requireSourceIds(String label, String text, List<String> sourceIds, Set<String> validSourceIds,
                                  List<String> violations) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sourceIds == null || sourceIds.isEmpty()) {
            violations.add(label + "缺少来源 ID。");
            return;
        }
        if (sourceIds.stream().anyMatch(sourceId -> !validSourceIds.contains(sourceId))) {
            violations.add(label + "引用了当前请求之外的来源。");
        }
    }

    private static final java.util.regex.Pattern DIGIT_RUNS = java.util.regex.Pattern.compile("\\d{2,}");

    /**
     * REP-01：叙述中的硬数字必须能在其引用来源内容中找到，防止数值凭空捏造或夸大；
     * 单个数字不检查，避免与中文数字写法差异造成误判。
     */
    private void requireNumericGrounding(String label, String text, List<String> sourceIds,
                                         List<ReportSource> sources, List<String> violations) {
        if (text == null || text.isBlank() || sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        StringBuilder evidence = new StringBuilder();
        for (ReportSource source : sources) {
            if (sourceIds.contains(source.sourceId()) && source.sanitizedContent() != null) {
                evidence.append(source.sanitizedContent().replaceAll("\\s+", ""));
            }
        }
        if (evidence.isEmpty()) {
            return;
        }
        java.util.regex.Matcher matcher = DIGIT_RUNS.matcher(text);
        Set<String> unsupported = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            if (!evidence.toString().contains(matcher.group())) {
                unsupported.add(matcher.group());
            }
        }
        if (!unsupported.isEmpty()) {
            violations.add(label + "中的数字细节未出现在引用来源中：" + String.join("、", unsupported));
        }
    }

    private void requireSemanticGrounding(String label, String text, List<String> sourceIds,
                                           List<ReportSource> sources, List<String> violations) {
        if (text == null || text.isBlank() || sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        String evidence = evidenceFor(sourceIds, sources);
        if (evidence.isBlank()) {
            return;
        }
        EvidenceClaimGrounding.Assessment assessment = claimGrounding.assess(text, evidence);
        if (!assessment.supported()) {
            violations.add(label + "中的陈述缺少引用来源词面支撑："
                    + String.join("；", assessment.unsupportedClaims()));
        }
    }

    private String evidenceFor(List<String> sourceIds, List<ReportSource> sources) {
        StringBuilder evidence = new StringBuilder();
        for (ReportSource source : sources) {
            if (!sourceIds.contains(source.sourceId())) {
                continue;
            }
            if (source.title() != null) {
                evidence.append(source.title()).append(' ');
            }
            if (source.sanitizedContent() != null) {
                evidence.append(source.sanitizedContent()).append(' ');
            }
            if (source.attributes() != null) {
                source.attributes().forEach((key, value) -> evidence.append(key).append(' ')
                        .append(value).append(' '));
            }
        }
        return evidence.toString();
    }

    private void validateMetricHighlight(MetricHighlight highlight, List<ReportSource> sources,
                                         Set<String> validSourceIds, List<String> violations) {
        requireSourceIds("指标亮点", highlight.summary(), highlight.sourceIds(), validSourceIds, violations);
        requireSemanticGrounding("指标亮点", highlight.summary(), highlight.sourceIds(), sources, violations);
        if (!matchesMetricSource(highlight, sources)) {
            violations.add("指标亮点与所引用的指标来源不完全一致。");
        }
    }

    private boolean matchesMetricSource(MetricHighlight highlight, List<ReportSource> sources) {
        if (highlight == null || highlight.sourceIds() == null || highlight.metricName() == null
                || highlight.metricValue() == null || highlight.unit() == null) {
            return false;
        }
        return highlight.sourceIds().stream()
                .flatMap(sourceId -> sources.stream().filter(source -> source.sourceId().equals(sourceId)))
                .anyMatch(source -> source.sourceType() == dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType.METRIC
                        && highlight.metricName().equals(source.attributes().get("name"))
                        && highlight.metricValue().equals(source.attributes().get("value"))
                        && highlight.unit().equals(source.attributes().get("unit")));
    }

    public record ValidationResult(boolean valid, List<String> violations) {
    }
}
