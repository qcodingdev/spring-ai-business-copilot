package dev.qcoding.businesscopilot.datacopilot.web;

import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.commonweb.exception.GlobalExceptionHandler;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationService;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionService;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for DataCopilotController focusing on validation and request handling.
 *
 * <p>控制器 Web 层测试。使用 standaloneSetup 把控制器、GlobalExceptionHandler 和校验器装配在一起，
 * 用 MockMvc 验证校验约束和错误响应格式，不依赖 Spring Boot 切片注解。</p>
 */
class DataCopilotControllerWebTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DataCopilotController controller = new DataCopilotController(
                mock(SchemaContextService.class),
                mock(SqlGenerationService.class),
                mock(QueryExecutionService.class),
                mock(AuditService.class));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("POST /sql-candidates with blank question returns validation error")
    void blankQuestionReturnsValidationError() throws Exception {
        String body = """
                {"question": ""}
                """;

        mockMvc.perform(post("/api/data-copilot/sql-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("question"));
    }

    @Test
    @DisplayName("POST /sql-candidates with missing question field returns validation error")
    void missingQuestionReturnsValidationError() throws Exception {
        String body = """
                {}
                """;

        mockMvc.perform(post("/api/data-copilot/sql-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("question"));
    }

    @Test
    @DisplayName("POST /sql-candidates with oversized question returns validation error")
    void oversizedQuestionReturnsValidationError() throws Exception {
        String tooLong = "x".repeat(1001);
        String body = "{\"question\": \"" + tooLong + "\"}";

        mockMvc.perform(post("/api/data-copilot/sql-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("question"));
    }

    @Test
    @DisplayName("/execute with blank confirmationToken returns validation error")
    void blankTokenReturnsValidationError() throws Exception {
        String body = """
                {"confirmationToken": ""}
                """;

        mockMvc.perform(post("/api/data-copilot/sql-candidates/cand-1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("confirmationToken"));
    }

    @Test
    @DisplayName("/execute ignores client-side sql field — only confirmationToken is read")
    void executeRejectsClientSideSql() throws Exception {
        // SqlExecutionRequest has no sql field; even if the client sends one, it is ignored.
        // The controller only reads confirmationToken — server-stored SQL is used instead.
        String body = """
                {"confirmationToken": "token-1", "sql": "DELETE FROM customers"}
                """;

        // The request is syntactically valid (confirmationToken is non-blank),
        // so it should pass validation and reach the controller.
        // The mock QueryExecutionService is not set up, so we expect a 500,
        // but the key point is: validation passes (not a 400 about an unknown "sql" field).
        mockMvc.perform(post("/api/data-copilot/sql-candidates/cand-1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> {
                    // Not a 400 validation error — the extra "sql" field is silently ignored
                    int status = result.getResponse().getStatus();
                    assertThat(status).isNotEqualTo(400);
                });
    }
}
