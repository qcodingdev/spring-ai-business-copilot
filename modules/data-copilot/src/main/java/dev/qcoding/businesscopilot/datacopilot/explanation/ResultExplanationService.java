package dev.qcoding.businesscopilot.datacopilot.explanation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 为已执行查询结果生成简洁业务解释的服务。
 *
 * <p>查询结果解释服务。根据用户问题、已执行 SQL 和脱敏后的查询结果，
 * 调用 LLM 生成简洁业务解释。</p>
 *
 * <p>核心安全约束：
 * <ul>
 *   <li>只把脱敏后的结果摘要传给模型，不传完整大结果；</li>
 *   <li>解释必须基于查询结果，不得编造数字；</li>
 *   <li>空结果返回友好解释；</li>
 *   <li>模型调用失败时返回降级解释，不影响表格结果展示；</li>
 *   <li>中文问题优先中文解释，英文问题优先英文解释。</li>
 * </ul></p>
 */
public class ResultExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ResultExplanationService.class);

    private static final String TEMPLATE_LOCATION = "data-copilot/result-explanation.st";

    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final QueryResultSummarizer summarizer;

    public ResultExplanationService(AiChatService aiChatService,
                                     PromptTemplateService promptTemplateService,
                                     QueryResultSummarizer summarizer) {
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.summarizer = summarizer;
    }

    /**
     * 为指定查询结果生成 AI 解释。
     *
     * @param request 包含问题、SQL 和脱敏结果的解释请求
     * @return 解释响应；模型失败时可能为降级响应
     */
    public ResultExplanationResponse explain(ResultExplanationRequest request) {
        // 1. 生成脱敏后的结果摘要
        String resultSummary = summarizer.summarize(request.result());

        // 2. 空结果直接返回友好解释，不调用模型
        if (request.result() != null && request.result().rows().isEmpty()) {
            String emptyExplanation = buildEmptyExplanation(request.question());
            return ResultExplanationResponse.success(emptyExplanation);
        }

        // 3. 渲染 prompt 模板
        String prompt = promptTemplateService.render(TEMPLATE_LOCATION, Map.of(
                "question", request.question(),
                "sql", request.sql(),
                "resultSummary", resultSummary));

        // 4. 调用模型，失败时降级
        try {
            String explanation = aiChatService.generateText("data.result-explanation", prompt);
            if (explanation == null || explanation.isBlank()) {
                log.warn("模型返回空解释，已使用降级说明");
                return ResultExplanationResponse.degraded(buildDegradedExplanation(request));
            }
            return ResultExplanationResponse.success(explanation.trim());
        } catch (Exception ex) {
            // 模型调用失败时返回降级解释，记录 warn 日志，不影响表格结果展示
            log.warn("AI 结果解释生成失败，已返回降级说明：{}", ex.getMessage());
            return ResultExplanationResponse.degraded(buildDegradedExplanation(request));
        }
    }

    /** 为空结果构建友好说明。 */
    private String buildEmptyExplanation(String question) {
        return "未查询到匹配数据。";
    }

    /** 在模型失败时构建降级说明。 */
    private String buildDegradedExplanation(ResultExplanationRequest request) {
        int rowCount = request.result() != null ? request.result().rowCount() : 0;
        return "查询已执行，返回 " + rowCount + " 行结果。AI 解释生成失败，请直接查看数据表格。";
    }
}
