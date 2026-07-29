package dev.qcoding.businesscopilot.resumecopilot.job;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobDraftServiceTest {

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
}
