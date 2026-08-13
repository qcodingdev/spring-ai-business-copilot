package dev.qcoding.businesscopilot.resumecopilot.assessment;

import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 强制评估绑定证据，并阻止自动招聘决策或涉及受保护属性的问题。 */
public class ResumeAssessmentGuardrail {
    private static final List<String> FORBIDDEN = List.of("建议录用", "建议淘汰", "建议拒绝", "不适合", "高潜", "稳定性差",
            "总分", "排名", "通过率", "通过概率", "百分位", "hire", "reject", "ranking", "overall score", "probability");
    private static final List<String> PROTECTED = List.of("年龄", "性别", "婚育", "婚姻", "民族", "宗教", "健康", "残障", "家庭",
            "籍贯", "政治面貌", "毕业年份", "应届", "年轻团队", "形象气质", "稳定性", "本地户口",
            "长期加班", "996");
    private static final Pattern PROTECTED_ENGLISH = Pattern.compile(
            "\\b(age|gender|marital|religion|health|disability|family|graduation year|recent graduate|"
                    + "young team|appearance|local household|culture fit|work overtime)\\b");

    public Validation validate(ResumeModels.AssessmentContent content, List<ResumeModels.JobCriterion> criteria,
                               List<ResumeModels.ResumeEvidence> evidence) {
        List<String> reasons = new ArrayList<>();
        if (content == null) return new Validation(false, List.of("AI 未返回结构化评估。"));
        Set<String> criterionIds = criteria.stream().map(ResumeModels.JobCriterion::criterionId).collect(java.util.stream.Collectors.toSet());
        Set<String> evidenceIds = evidence.stream().map(ResumeModels.ResumeEvidence::evidenceId).collect(java.util.stream.Collectors.toSet());
        Set<String> assessed = new HashSet<>();
        for (var item : content.criterionAssessments()) {
            if (item == null || item.status() == null || !criterionIds.contains(item.criterionId())) {
                reasons.add("评估引用了未知职位标准。");
                continue;
            }
            if (!assessed.add(item.criterionId())) {
                reasons.add("同一职位标准在评估中重复出现。");
            }
            if (item.evidenceIds().stream().anyMatch(id -> !evidenceIds.contains(id))) {
                reasons.add("评估引用了当前简历之外的证据。");
            }
            if (item.status() == ResumeModels.MatchStatus.NOT_FOUND) {
                if (!item.evidenceIds().isEmpty()) reasons.add("NOT_FOUND 状态不能声称存在简历证据。");
            } else if (item.evidenceIds().isEmpty()) {
                reasons.add(item.status() + " 状态至少需要一个当前简历证据 ID。");
            }
        }
        if (!assessed.containsAll(criterionIds)) reasons.add("每条已确认职位标准都必须出现在评估中。");
        String allText = flatten(content).toLowerCase(Locale.ROOT);
        if (FORBIDDEN.stream().anyMatch(allText::contains) || allText.matches("(?s).*\\b\\d{1,3}%.*")) {
            reasons.add("评估包含自动录用决定、评分、排名或概率。");
        }
        if (PROTECTED.stream().anyMatch(allText::contains) || PROTECTED_ENGLISH.matcher(allText).find()) {
            reasons.add("评估包含受保护属性或代理招聘条件。");
        }
        if (!"en-US".equals(BusinessRequestContextHolder.currentLocale()) && !usesChineseNarrative(content)) {
            reasons.add("评估草稿必须使用简体中文，技术专有名词除外。");
        }
        for (var question : content.interviewQuestions()) {
            if (question == null) {
                reasons.add("面试问题内容不完整。");
                continue;
            }
            String text = question.question() == null ? "" : question.question().toLowerCase(Locale.ROOT);
            if (question.criterionId() == null || question.question() == null || question.question().isBlank()) {
                reasons.add("面试问题内容不完整。");
            }
            if (!criterionIds.contains(question.criterionId())) reasons.add("面试问题引用了未知职位标准。");
            if (question.evidenceIds().stream().anyMatch(id -> !evidenceIds.contains(id))) {
                reasons.add("面试问题引用了当前简历之外的证据。");
            }
            if (PROTECTED.stream().anyMatch(text::contains) || PROTECTED_ENGLISH.matcher(text).find()) {
                reasons.add("面试问题涉及受保护属性。");
            }
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

    private boolean usesChineseNarrative(ResumeModels.AssessmentContent content) {
        return containsChinese(content.anonymousSummary())
                && content.criterionAssessments().stream().filter(Objects::nonNull)
                .allMatch(item -> containsChinese(item.explanation()))
                && content.evidenceGaps().stream().filter(Objects::nonNull).allMatch(this::containsChinese)
                && content.interviewQuestions().stream().filter(Objects::nonNull)
                .allMatch(item -> containsChinese(item.question()))
                && content.limitations().stream().filter(Objects::nonNull).allMatch(this::containsChinese);
    }

    private boolean containsChinese(String value) {
        if (value == null || value.isBlank()) return false;
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    public record Validation(boolean valid, List<String> reasons) { }
}
