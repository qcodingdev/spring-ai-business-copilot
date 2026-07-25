package dev.qcoding.businesscopilot.resumecopilot.job;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;

import java.util.List;
import java.util.Map;

/** 从业务需求生成岗位画像和 JD 草稿，不直接形成招聘决定。 */
public class JobDraftService {

    private static final String PROMPT = "resume-copilot/job-draft-generation.st";
    private final ResumePrivacySanitizer sanitizer;
    private final AiChatService ai;
    private final PromptTemplateService prompts;

    public JobDraftService(ResumePrivacySanitizer sanitizer, AiChatService ai,
                           PromptTemplateService prompts) {
        this.sanitizer = sanitizer;
        this.ai = ai;
        this.prompts = prompts;
    }

    /** Backward-compatible entry point for integrations that only provide requirements. */
    public JobDraftResponse generate(String requirements) {
        return generate(null, requirements);
    }

    public JobDraftResponse generate(String requestedTitle, String requirements) {
        if (requirements == null || requirements.isBlank() || requirements.length() > 2000) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "岗位需求不能为空且不能超过 2000 个字符。");
        }
        if (requestedTitle != null && requestedTitle.length() > 300) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "职位名称不能超过 300 个字符。");
        }
        String title = clean(requestedTitle);
        String sanitized = sanitizer.sanitizeJobDescription(requirements);
        RenderedPrompt prompt = prompts.renderWithMetadata(
                PROMPT, "v1.1", Map.of(
                        "jobTitle", title.isBlank() ? "请根据岗位需求确定职位名称" : title,
                        "jobRequirements", sanitized));
        AiInvocationResult<LlmJobDraftOutput> invocation = ai.generateJsonWithMetadata(
                "resume.job-draft", prompt.content(), LlmJobDraftOutput.class);
        LlmJobDraftOutput output = invocation.content();
        if (output == null || output.title() == null || output.title().isBlank()
                || output.jdDraft() == null || output.jdDraft().isBlank()) {
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR);
        }
        AiInvocationMetadata metadata = invocation.metadata();
        String resolvedTitle = title.isBlank() ? clean(output.title()) : title;
        if (isPlaceholderOutput(output)) {
            InputDerivedDraft derived = deriveFromRequirements(resolvedTitle, sanitized);
            return new JobDraftResponse(
                    resolvedTitle,
                    derived.profile(),
                    derived.responsibilities(),
                    derived.requiredQualifications(),
                    derived.preferredQualifications(),
                    completeJdDraft(resolvedTitle, sanitized, derived),
                    List.of("已根据当前填写的岗位需求生成草稿；请补充未在原始需求中说明的组织信息。"),
                    metadata == null ? ai.modelName() : metadata.modelName(),
                    List.of("岗位画像和 JD 是待编辑草稿，岗位标准确认后才可用于简历证据分析。"));
        }
        return new JobDraftResponse(
                resolvedTitle,
                clean(output.jobProfile()),
                clean(output.responsibilities()),
                clean(output.requiredQualifications()),
                clean(output.preferredQualifications()),
                completeJdDraft(resolvedTitle, sanitized, output),
                clean(output.verificationNotes()),
                metadata == null ? ai.modelName() : metadata.modelName(),
                List.of("岗位画像和 JD 是待编辑草稿，岗位标准确认后才可用于简历证据分析。"));
    }

    private String clean(String value) {
        return value == null ? "" : sanitizer.sanitizeJobDescription(value);
    }

    private List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(this::clean).limit(30).toList();
    }

    /**
     * A model occasionally returns a one-line JD despite the schema being valid. Keep the LLM content,
     * but add the missing editable sections so the recruiter can always continue to criteria extraction.
     */
    private String completeJdDraft(String title, String requirements, LlmJobDraftOutput output) {
        String draft = clean(output.jdDraft());
        long sections = List.of("岗位概述", "主要职责", "任职资格", "加分项", "协作对象", "90 天", "待确认")
                .stream().filter(draft::contains).count();
        if (sections >= 5) {
            return draft;
        }
        return completeJdDraft(title, requirements, new InputDerivedDraft(
                clean(output.jobProfile()),
                clean(output.responsibilities()),
                clean(output.requiredQualifications()),
                clean(output.preferredQualifications())));
    }

    private String completeJdDraft(String title, String requirements, InputDerivedDraft draft) {
        String profile = draft.profile().isBlank()
                ? "围绕“" + title + "”的已填写岗位需求开展工作。" : draft.profile();
        return """
                # %s

                ## 岗位概述
                %s

                ## 主要职责
                %s

                ## 任职资格（必需）
                %s

                ## 加分项
                %s

                ## 协作对象
                - 待招聘负责人确认

                ## 入职后 90 天工作目标
                - 了解岗位范围和现有工作流程，并与负责人确认阶段目标。
                - 根据已确认的岗位职责完成首个可核验的工作交付。

                ## 招聘负责人待确认事项
                - 团队协作方式、业务范围和优先级。
                - 工作地点、薪酬福利及其他未在原始需求中提供的事项。

                ## 原始岗位需求参考
                %s
                """.formatted(
                title,
                profile,
                bullets(draft.responsibilities(), requirements),
                bullets(draft.requiredQualifications(), requirements),
                bullets(draft.preferredQualifications(), "具备与岗位需求相关的补充经验"),
                requirements);
    }

    /** Treat an all-placeholder model response as invalid business content, even when its JSON is valid. */
    private boolean isPlaceholderOutput(LlmJobDraftOutput output) {
        String combined = String.join("\n",
                clean(output.jobProfile()), clean(output.jdDraft()),
                String.join("\n", clean(output.responsibilities())),
                String.join("\n", clean(output.requiredQualifications())),
                String.join("\n", clean(output.preferredQualifications())));
        return occurrences(combined, "待招聘负责人确认") + occurrences(combined, "待确认") >= 3;
    }

    private int occurrences(String text, String phrase) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(phrase, index)) >= 0) {
            count++;
            index += phrase.length();
        }
        return count;
    }

    /** Derives usable draft content from the recruiter input when the model returns only placeholders. */
    private InputDerivedDraft deriveFromRequirements(String title, String requirements) {
        List<String> statements = java.util.Arrays.stream(requirements.split("[。；;\\n]+"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        List<String> responsibilities = statements.stream()
                .filter(value -> value.contains("负责") || value.contains("职责"))
                .flatMap(value -> splitItems(afterAny(value, "主要负责", "负责", "职责")).stream())
                .toList();
        List<String> required = statements.stream()
                .filter(value -> value.contains("要求") || value.contains("具备") || value.contains("需要"))
                .flatMap(value -> splitItems(afterAny(value, "要求", "具备", "需要")).stream())
                .filter(value -> !value.contains("优先"))
                .toList();
        List<String> preferred = statements.stream()
                .filter(value -> value.contains("优先"))
                .map(value -> value.replaceFirst("^.*?有", "有").trim())
                .toList();
        if (responsibilities.isEmpty()) responsibilities = List.of("根据已填写的岗位需求推进相关业务工作");
        if (required.isEmpty()) required = List.of("具备完成上述岗位职责所需的相关经验与能力");
        String profile = title + "负责" + String.join("、", responsibilities) + "。";
        return new InputDerivedDraft(profile, responsibilities, required, preferred);
    }

    private String afterAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            int index = value.indexOf(prefix);
            if (index >= 0) return value.substring(index + prefix.length()).replaceFirst("^[：:，,\\s]+", "").trim();
        }
        return value;
    }

    private List<String> splitItems(String value) {
        return java.util.Arrays.stream(value.split("[、，,]"))
                .map(String::trim).filter(item -> item.length() >= 2).toList();
    }

    private String bullets(List<String> values, String fallback) {
        if (values.isEmpty()) {
            return "- " + fallback;
        }
        return values.stream().map(value -> "- " + value).collect(java.util.stream.Collectors.joining("\n"));
    }

    private record InputDerivedDraft(String profile, List<String> responsibilities,
                                     List<String> requiredQualifications,
                                     List<String> preferredQualifications) {
    }

    public record LlmJobDraftOutput(
            String title,
            String jobProfile,
            List<String> responsibilities,
            List<String> requiredQualifications,
            List<String> preferredQualifications,
            String jdDraft,
            List<String> verificationNotes) {
    }

    public record JobDraftResponse(
            String title,
            String jobProfile,
            List<String> responsibilities,
            List<String> requiredQualifications,
            List<String> preferredQualifications,
            String jdDraft,
            List<String> verificationNotes,
            String modelName,
            List<String> limitations) {
    }
}
