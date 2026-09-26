package dev.qcoding.businesscopilot.e2e;

import dev.qcoding.businesscopilot.aicore.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import static org.mockito.Mockito.*;

/** Test-only model boundary. Real controllers, guardrails, repositories and runtime stay active. */
@TestConfiguration(proxyBeanMethods = false)
public class ControlledModelConfiguration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String POLICY = "虚构服务流程：请提供订单编号，客服核验后回复处理进度。";

    @Bean @Primary
    AiChatService controlledChat() {
        return mock(AiChatService.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("isModelEnabled")) return true;
            if (method.equals("modelName")) return "controlled-business-fixture";
            if (method.equals("generateText")) return "查询已完成，结果仅包含受控业务数据。";
            if (!method.startsWith("generate")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            Object[] args = invocation.getArguments();
            String operation = (String) args[0], prompt = (String) args[1];
            Class<?> type = (Class<?>) args[2];
            AiAttemptObserver observer = args.length > 3 ? (AiAttemptObserver) args[3] : AiAttemptObserver.noOp();
            String attempt = observer.beforeAttempt(operation, "test-fixture", "controlled-business-fixture", 100);
            var metadata = new AiInvocationMetadata("test-fixture", "controlled-business-fixture",
                    UUID.randomUUID().toString(), 100, 50, "STOP", 1);
            try {
                Object value = JSON.convertValue(output(type.getSimpleName(), prompt), type);
                observer.afterAttempt(attempt, metadata, null);
                return new AiInvocationResult<>(value, metadata);
            } catch (RuntimeException ex) {
                observer.afterAttempt(attempt, metadata, ex);
                throw ex;
            }
        });
    }

    @Bean @Primary
    AiEmbeddingService controlledEmbedding() {
        return mock(AiEmbeddingService.class, invocation -> {
            if (invocation.getMethod().getName().equals("isModelEnabled")) return true;
            if (invocation.getMethod().getName().equals("embed")) {
                String text = (String) invocation.getArguments()[invocation.getArguments().length - 1];
                float[] vector = new float[1536];
                text.codePoints().forEach(c -> vector[Math.floorMod(c, vector.length)] += 1);
                return vector;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static Object output(String type, String prompt) {
        return switch (type) {
            case "GeneratedSqlCandidate" -> Map.of("sql", "SELECT count(*) AS order_count FROM public.orders WHERE created_at >= '2026-09-01' AND created_at < '2026-09-08' LIMIT 1",
                    "summary", "统计订单数量", "assumptions", List.of(), "warnings", List.of());
            case "LlmAnswerOutput" -> Map.of("status", "ANSWERED", "answer", POLICY,
                    "citations", List.of(Map.of("chunkId", Long.parseLong(match(prompt, "chunkId=(\\d+)")))), "warnings", List.of());
            case "LlmClassificationOutput" -> Map.of("category", "OTHER", "sentiment", "NEUTRAL", "urgency", "LOW",
                    "summary", "查询服务处理流程", "needsHuman", true, "reasons", List.of());
            case "LlmReplyDraftOutput" -> Map.of("replyText", POLICY, "riskLevel", "LOW", "riskReasons", List.of(),
                    "needsHuman", true, "citations", List.of(Map.of("chunkId", match(prompt, "ChunkID: (\\S+)"), "reason", "服务流程依据")));
            case "LlmReportOutput" -> {
                String source = match(prompt, "(?m)^sourceId=([^\\n]+)");
                String title = match(prompt.substring(prompt.indexOf("sourceId=")), "(?m)^title=([^\\n]+)");
                yield Map.of("executiveSummary", title, "executiveSummarySourceIds", List.of(source),
                        "metricHighlights", List.of(), "completedItems", List.of(), "risks", List.of(),
                        "actionItems", List.of(), "suggestions", List.of(),
                        "citations", List.of(Map.of("sourceId", source, "reason", "来源标题支持该摘要")));
            }
            case "LlmJobDraftOutput" -> Map.of("title", "Java 工程师", "jobProfile", "负责 Java 服务开发。",
                    "responsibilities", List.of("负责 Java 服务开发。"), "requiredQualifications", List.of("具备 Java 开发经验。"),
                    "preferredQualifications", List.of(), "jdDraft", "负责 Java 服务开发。", "verificationNotes", List.of("需要人工核验岗位要求。"));
            case "LlmJobCriteriaOutput" -> Map.of("criteria", List.of(Map.of("criterionId", "fixture",
                    "category", "SKILL", "requirementType", "REQUIRED", "description", "负责 Java 服务开发。",
                    "normalizedKeywords", List.of("Java"), "sourceText", "负责 Java 服务开发。")));
            case "AssessmentContent" -> assessment(prompt);
            default -> throw new IllegalStateException("No controlled fixture for " + type);
        };
    }

    private static Object assessment(String prompt) {
        String criteria = between(prompt, "<criteria>", "</criteria>");
        String evidence = between(prompt, "<evidence>", "</evidence>");
        String evidenceId = evidence.lines().findFirst().orElseThrow().split(" \\| ")[0];
        List<Map<String, Object>> assessments = new ArrayList<>();
        for (String criterion : criteria.split("\\n")) {
            assessments.add(Map.of("criterionId", criterion.split(" \\| ")[0], "status", "SUPPORTED",
                    "explanation", "简历陈述了 Java 服务开发经历，需人工核验。",
                    "evidenceIds", List.of(evidenceId)));
        }
        return Map.of("anonymousSummary", "简历陈述了 Java 服务开发经历。", "criterionAssessments", assessments,
                "evidenceGaps", List.of(), "interviewQuestions", List.of(), "limitations", List.of("仅作人工核验辅助。"));
    }

    private static String between(String text, String start, String end) {
        return text.substring(text.indexOf(start) + start.length(), text.indexOf(end)).trim();
    }
    private static String match(String text, String regex) {
        var matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) throw new IllegalStateException("Required fixture evidence missing: " + regex);
        return matcher.group(1);
    }
}
