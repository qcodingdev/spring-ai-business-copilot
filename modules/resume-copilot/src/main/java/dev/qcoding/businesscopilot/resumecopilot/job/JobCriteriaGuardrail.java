package dev.qcoding.businesscopilot.resumecopilot.job;

import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class JobCriteriaGuardrail {
    private static final List<String> FORBIDDEN = List.of("年龄", "性别", "婚育", "婚姻", "民族", "宗教", "健康", "残障",
            "政治面貌", "籍贯", "照片", "年轻", "稳定性", "毕业年份", "应届", "本地户口", "形象气质",
            "家庭稳定", "长期加班", "996");
    private static final Pattern FORBIDDEN_ENGLISH = Pattern.compile(
            "\\b(age|gender|marital|religion|disability|graduation year|recent graduate|young team|"
                    + "appearance|local household|family status|culture fit|work overtime)\\b");
    private final ResumeCopilotProperties properties;

    public JobCriteriaGuardrail(ResumeCopilotProperties properties) {
        this.properties = properties;
    }

    public Validation validate(List<ResumeModels.JobCriterion> criteria, String sanitizedJd) {
        List<String> reasons = new ArrayList<>();
        if (criteria == null || criteria.isEmpty()) reasons.add("未提取到职位标准。");
        if (criteria != null && criteria.size() > properties.maxCriteriaCount()) reasons.add("提取的职位标准数量超过限制。");
        if (criteria != null) {
            for (ResumeModels.JobCriterion criterion : criteria) {
                if (criterion == null || criterion.category() == null || criterion.requirementType() == null
                        || isBlank(criterion.description()) || isBlank(criterion.sourceText())) {
                    reasons.add("职位标准内容不完整。");
                    continue;
                }
                if (!sanitizedJd.contains(criterion.sourceText().trim())) {
                    reasons.add("职位标准无法追溯到原始职位描述。");
                }
                String text = (criterion.description() + " " + criterion.sourceText()).toLowerCase(Locale.ROOT);
                if (FORBIDDEN.stream().anyMatch(text::contains) || FORBIDDEN_ENGLISH.matcher(text).find()) {
                    reasons.add("职位标准使用了受保护属性或主观招聘标签。");
                }
            }
        }
        return new Validation(reasons.isEmpty(), List.copyOf(reasons));
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    public record Validation(boolean valid, List<String> reasons) { }
}
