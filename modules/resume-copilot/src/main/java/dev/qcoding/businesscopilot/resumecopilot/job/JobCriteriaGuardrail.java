package dev.qcoding.businesscopilot.resumecopilot.job;

import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JobCriteriaGuardrail {
    private static final List<String> FORBIDDEN = List.of("年龄", "性别", "婚育", "婚姻", "民族", "宗教", "健康", "残障",
            "政治面貌", "籍贯", "照片", "年轻", "稳定性", "age", "gender", "marital", "religion", "disability");
    private final ResumeCopilotProperties properties;

    public JobCriteriaGuardrail(ResumeCopilotProperties properties) {
        this.properties = properties;
    }

    public Validation validate(List<ResumeModels.JobCriterion> criteria, String sanitizedJd) {
        List<String> reasons = new ArrayList<>();
        if (criteria == null || criteria.isEmpty()) reasons.add("No job criteria were extracted.");
        if (criteria != null && criteria.size() > properties.maxCriteriaCount()) reasons.add("Too many job criteria were extracted.");
        if (criteria != null) {
            for (ResumeModels.JobCriterion criterion : criteria) {
                if (criterion == null || criterion.category() == null || criterion.requirementType() == null
                        || isBlank(criterion.description()) || isBlank(criterion.sourceText())) {
                    reasons.add("A job criterion is incomplete.");
                    continue;
                }
                if (!sanitizedJd.contains(criterion.sourceText().trim())) {
                    reasons.add("A job criterion cannot be traced to the job description.");
                }
                String text = (criterion.description() + " " + criterion.sourceText()).toLowerCase(Locale.ROOT);
                if (FORBIDDEN.stream().anyMatch(text::contains)) {
                    reasons.add("A job criterion uses a protected attribute or subjective hiring label.");
                }
            }
        }
        return new Validation(reasons.isEmpty(), List.copyOf(reasons));
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    public record Validation(boolean valid, List<String> reasons) { }
}
