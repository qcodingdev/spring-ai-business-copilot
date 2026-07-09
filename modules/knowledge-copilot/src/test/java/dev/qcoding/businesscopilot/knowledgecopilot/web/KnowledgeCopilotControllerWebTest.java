package dev.qcoding.businesscopilot.knowledgecopilot.web;

import dev.qcoding.businesscopilot.commonweb.exception.GlobalExceptionHandler;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerStatus;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeCitation;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeQuestionService;
import dev.qcoding.businesscopilot.knowledgecopilot.audit.KnowledgeAuditService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for KnowledgeCopilotController focusing on validation and request handling.
 *
 * <p>控制器 Web 层测试。使用 standaloneSetup，验证校验约束和错误响应格式。</p>
 */
class KnowledgeCopilotControllerWebTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new KnowledgeCopilotController(
                mock(DocumentUploadService.class),
                mock(KnowledgeDocumentRepository.class),
                mock(KnowledgeQuestionService.class),
                mock(KnowledgeAuditService.class));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 文档上传校验
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /documents with blank fileName returns validation error")
    void blankFileNameReturnsValidationError() throws Exception {
        String body = """
                {"fileName": "", "content": "some content"}
                """;

        mockMvc.perform(post("/api/knowledge-copilot/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("fileName"));
    }

    @Test
    @DisplayName("POST /documents with blank content returns validation error")
    void blankContentReturnsValidationError() throws Exception {
        String body = """
                {"fileName": "test.md", "content": ""}
                """;

        mockMvc.perform(post("/api/knowledge-copilot/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("content"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 问答校验
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /questions with blank question returns validation error")
    void blankQuestionReturnsValidationError() throws Exception {
        String body = """
                {"question": ""}
                """;

        mockMvc.perform(post("/api/knowledge-copilot/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("question"));
    }

    @Test
    @DisplayName("POST /questions with missing question field returns validation error")
    void missingQuestionReturnsValidationError() throws Exception {
        String body = "{}";

        mockMvc.perform(post("/api/knowledge-copilot/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("question"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 启用/停用校验
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PATCH /documents/{id}/enabled with valid body succeeds")
    void validEnableRequestSucceeds() throws Exception {
        String body = """
                {"enabled": true}
                """;

        // Not setting up documentRepository mock — will get 404,
        // but the key point is validation passes (not a 400).
        mockMvc.perform(patch("/api/knowledge-copilot/documents/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }
}
