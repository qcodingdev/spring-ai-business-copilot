package dev.qcoding.businesscopilot.resumecopilot.job;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobDraftServiceTest {

    @AfterEach
    void clearRequestContext() {
        BusinessRequestContextHolder.clear();
    }

    @Test
    void preservesExplicitInputAndRejectsModelAddedQualifications() {
        AiChatService ai = mock(AiChatService.class);
        JobDraftService.LlmJobDraftOutput modelOutput = new JobDraftService.LlmJobDraftOutput(
                "Java 后端工程师", "负责数据分析和 BI 看板", List.of("开发数据仓库"),
                List.of("统计学本科", "Python 经验"), List.of("金融行业经验"),
                "# Java 后端工程师\n\n无依据的数据分析草稿",
                List.of());
        when(ai.generateJsonWithMetadata(anyString(), anyString(),
                any(Class.class))).thenReturn(new AiInvocationResult<>(modelOutput, null));
        when(ai.modelName()).thenReturn("test-model");
        JobDraftService service = new JobDraftService(
                new ResumePrivacySanitizer(new ResumeCopilotProperties(
                        true, 12000, 20000, 30, 80,
                        Duration.ofMinutes(30), true)),
                ai, new PromptTemplateService());

        JobDraftService.JobDraftResponse response = service.generate(
                "Java 后端工程师",
                "必选：3 年以上 Java 后端开发经验。必选：使用 Spring Boot 构建 REST API 的实践经验。"
                        + "必选：具备 PostgreSQL 或其他关系型数据库经验。加分：有容器化服务实践。");

        assertThat(response.requiredQualifications())
                .contains("3 年以上 Java 后端开发经验", "使用 Spring Boot 构建 REST API 的实践经验")
                .anyMatch(value -> value.contains("PostgreSQL"));
        assertThat(response.preferredQualifications()).contains("有容器化服务实践");
        assertThat(response.jdDraft())
                .contains("3 年以上 Java 后端开发经验")
                .contains("使用 Spring Boot 构建 REST API 的实践经验")
                .contains("有容器化服务实践")
                .doesNotContain("统计学本科", "Python 经验", "金融行业经验", "数据仓库");
    }

    @Test
    void englishLocaleUsesEnglishPromptAndDeterministicDraftCompletion() {
        BusinessRequestContextHolder.set(new BusinessRequestContext(
                "request-en", "operator", Set.of("OPERATOR"), "en-US"));
        AiChatService ai = mock(AiChatService.class);
        JobDraftService.LlmJobDraftOutput modelOutput = new JobDraftService.LlmJobDraftOutput(
                "Java Backend Engineer", "Build backend services", List.of("Build APIs"),
                List.of("Unapproved degree requirement"), List.of(),
                "# Java Backend Engineer\n\nShort draft", List.of());
        when(ai.generateJsonWithMetadata(anyString(), anyString(),
                any(Class.class))).thenReturn(new AiInvocationResult<>(modelOutput, null));
        when(ai.modelName()).thenReturn("test-model");
        JobDraftService service = new JobDraftService(
                new ResumePrivacySanitizer(new ResumeCopilotProperties(
                        true, 12000, 20000, 30, 80,
                        Duration.ofMinutes(30), true)),
                ai, new PromptTemplateService());

        JobDraftService.JobDraftResponse response = service.generate(
                "Java Backend Engineer",
                "Responsibilities: Build REST APIs\nRequired: 3 years of Java experience\n"
                        + "Preferred: Docker experience");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).generateJsonWithMetadata(eq("resume.job-draft"), prompt.capture(), any(Class.class));
        assertThat(prompt.getValue()).contains("You are a job-requirements drafting assistant");
        assertThat(response.requiredQualifications()).containsExactly("3 years of Java experience");
        assertThat(response.preferredQualifications()).containsExactly("Docker experience");
        assertThat(response.jdDraft())
                .contains("## Role Overview", "## Required Qualifications", "## First 90 Days")
                .doesNotContain("岗位概述", "Unapproved degree requirement");
        assertThat(response.limitations()).allMatch(value -> !value.matches(".*[\\u4E00-\\u9FFF].*"));
    }

    @Test
    void englishQuickStartPreservesNaturalLanguageResponsibilitiesWithoutLabels() {
        BusinessRequestContextHolder.set(new BusinessRequestContext(
                "request-en-natural", "operator", Set.of("OPERATOR"), "en-US"));
        AiChatService ai = mock(AiChatService.class);
        JobDraftService.LlmJobDraftOutput modelOutput = new JobDraftService.LlmJobDraftOutput(
                "Fictional AI Application Engineer", "To be confirmed by the hiring owner",
                List.of(), List.of(), List.of(), "Short draft", List.of());
        when(ai.generateJsonWithMetadata(anyString(), anyString(),
                any(Class.class))).thenReturn(new AiInvocationResult<>(modelOutput, null));
        when(ai.modelName()).thenReturn("test-model");
        JobDraftService service = new JobDraftService(
                new ResumePrivacySanitizer(new ResumeCopilotProperties(
                        true, 12000, 20000, 30, 80,
                        Duration.ofMinutes(30), true)),
                ai, new PromptTemplateService());

        JobDraftService.JobDraftResponse response = service.generate(
                "Fictional AI Application Engineer",
                "Build Spring Boot and Spring AI business applications, including RAG and tool integrations; "
                        + "independently diagnose production issues and verify delivery with automated tests.");

        assertThat(response.responsibilities())
                .containsExactly(
                        "Build Spring Boot and Spring AI business applications, including RAG and tool integrations",
                        "independently diagnose production issues and verify delivery with automated tests.");
        assertThat(response.requiredQualifications()).singleElement()
                .asString().contains("Spring Boot", "production issues", "automated tests");
        assertThat(response.jobProfile())
                .isEqualTo("Fictional AI Application Engineer is responsible for: "
                        + "Build Spring Boot and Spring AI business applications, including RAG and tool integrations; "
                        + "independently diagnose production issues and verify delivery with automated tests.");
        assertThat(response.jdDraft())
                .contains("Build Spring Boot and Spring AI business applications")
                .contains("independently diagnose production issues")
                .doesNotContain("Perform the business work described in the supplied job requirements")
                .doesNotContain("岗位概述");
    }
}
