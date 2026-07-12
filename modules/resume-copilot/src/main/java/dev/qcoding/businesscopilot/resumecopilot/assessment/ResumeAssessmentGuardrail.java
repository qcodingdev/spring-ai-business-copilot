package dev.qcoding.businesscopilot.resumecopilot.assessment;

import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Enforces evidence binding and blocks automated hiring decisions or protected-attribute questions. */
public class ResumeAssessmentGuardrail {
    private static final List<String> FORBIDDEN = List.of("建议录用", "建议淘汰", "建议拒绝", "不适合", "高潜", "稳定性差",
            "总分", "排名", "通过率", "通过概率", "百分位", "hire", "reject", "ranking", "overall score", "probability");
    private static final List<String> PROTECTED = List.of("年龄", "性别", "婚育", "婚姻", "民族", "宗教", "健康", "残障", "家庭",
            "籍贯", "政治面貌", "age", "gender", "marital", "religion", "health", "disability", "family");

    public Validation validate(ResumeModels.AssessmentContent content, List<ResumeModels.JobCriterion> criteria,
                               List<ResumeModels.ResumeEvidence> evidence) {
        List<String> reasons = new ArrayList<>();
        if (content == null) return new Validation(false, List.of("AI returned no structured assessment."));
        Set<String> criterionIds = criteria.stream().map(ResumeModels.JobCriterion::criterionId).collect(java.util.stream.Collectors.toSet());
        Set<String> evidenceIds = evidence.stream().map(ResumeModels.ResumeEvidence::evidenceId).collect(java.util.stream.Collectors.toSet());
        Set<String> assessed = new HashSet<>();
        for (var item : content.criterionAssessments()) {
            if (item == null || item.status() == null || !criterionIds.contains(item.criterionId())) {
                reasons.add("An assessment refers to an unknown job criterion.");
                continue;
            }
            if (!assessed.add(item.criterionId())) {
                reasons.add("A job criterion appears more than once in the assessment.");
            }
            if (item.evidenceIds().stream().anyMatch(id -> !evidenceIds.contains(id))) {
                reasons.add("An assessment refers to evidence outside the current resume.");
            }
            if (item.status() == ResumeModels.MatchStatus.NOT_FOUND) {
                if (!item.evidenceIds().isEmpty()) reasons.add("NOT_FOUND must not claim resume evidence.");
            } else if (item.evidenceIds().isEmpty()) {
                reasons.add(item.status() + " requires at least one current resume evidence ID.");
            }
        }
        if (!assessed.containsAll(criterionIds)) reasons.add("Every confirmed job criterion must appear in the assessment.");
        String allText = flatten(content).toLowerCase(Locale.ROOT);
        if (FORBIDDEN.stream().anyMatch(allText::contains) || allText.matches("(?s).*\\b\\d{1,3}%.*")) {
            reasons.add("Assessment contains an automated hiring decision, score, rank, or probability.");
        }
        for (var question : content.interviewQuestions()) {
            if (question == null) {
                reasons.add("An interview question is incomplete.");
                continue;
            }
            String text = question.question() == null ? "" : question.question().toLowerCase(Locale.ROOT);
            if (question.criterionId() == null || question.question() == null || question.question().isBlank()) {
                reasons.add("An interview question is incomplete.");
            }
            if (!criterionIds.contains(question.criterionId())) reasons.add("An interview question refers to an unknown criterion.");
            if (question.evidenceIds().stream().anyMatch(id -> !evidenceIds.contains(id))) {
                reasons.add("An interview question refers to evidence outside the current resume.");
            }
            if (PROTECTED.stream().anyMatch(text::contains)) reasons.add("An interview question asks about a protected attribute.");
        }
        return new Validation(reasons.isEmpty(), List.copyOf(reasons));
    }

    public ResumeModels.AssessmentContent normalizeNotFound(ResumeModels.AssessmentContent content) {
        var items = content.criterionAssessments().stream().map(item -> item.status() == ResumeModels.MatchStatus.NOT_FOUND
                ? new ResumeModels.CriterionAssessment(item.criterionId(), item.status(),
                "简历中未找到相关信息，需人工核验", List.of()) : item).toList();
        return new ResumeModels.AssessmentContent(content.anonymousSummary(), items, content.evidenceGaps(),
                content.interviewQuestions(), content.limitations());
    }

    private String flatten(ResumeModels.AssessmentContent content) {
        return String.join(" ", content.anonymousSummary() == null ? "" : content.anonymousSummary(),
                joinStrings(content.evidenceGaps()), joinStrings(content.limitations()),
                content.criterionAssessments().stream().filter(Objects::nonNull)
                        .map(item -> item.explanation() == null ? "" : item.explanation())
                        .collect(java.util.stream.Collectors.joining(" ")),
                content.interviewQuestions().stream().filter(Objects::nonNull)
                        .map(item -> item.question() == null ? "" : item.question())
                        .collect(java.util.stream.Collectors.joining(" ")));
    }

    private String joinStrings(List<String> values) {
        return values.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(" "));
    }

    public record Validation(boolean valid, List<String> reasons) { }
}
