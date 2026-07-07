package dev.qcoding.businesscopilot.datacopilot.web;

import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.audit.QueryAuditLog;
import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationRequest;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationResponse;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationService;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionService;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContext;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API that wires the Data Copilot pipeline into a complete flow.
 *
 * <p>Data Copilot REST 控制器。把 schema、SQL 生成、确认、执行、解释、审计能力串成闭环：
 * <ul>
 *   <li>GET  /schema — 返回白名单 schema 摘要；</li>
 *   <li>POST /sql-candidates — 生成 SQL，guardrails 校验，通过时保存候选并返回 token；</li>
 *   <li>POST /sql-candidates/{candidateId}/execute — 凭 token 取服务端候选，执行 SQL，脱敏，解释，审计；</li>
 *   <li>GET  /audit-logs — 返回最近审计日志。</li>
 * </ul>
 * 执行请求体只允许 confirmationToken，不允许传 SQL。执行编排与审计统一由
 * {@link QueryExecutionService} 处理，确保审计记录携带完整 requestId/userQuestion/modelName 上下文。</p>
 */
@RestController
@RequestMapping("/api/data-copilot")
public class DataCopilotController {

    private final SchemaContextService schemaContextService;
    private final SqlGenerationService sqlGenerationService;
    private final QueryExecutionService queryExecutionService;
    private final AuditService auditService;

    public DataCopilotController(SchemaContextService schemaContextService,
                                  SqlGenerationService sqlGenerationService,
                                  QueryExecutionService queryExecutionService,
                                  AuditService auditService) {
        this.schemaContextService = schemaContextService;
        this.sqlGenerationService = sqlGenerationService;
        this.queryExecutionService = queryExecutionService;
        this.auditService = auditService;
    }

    /** GET /api/data-copilot/schema — 返回白名单 schema 摘要 */
    @GetMapping("/schema")
    public ResponseEntity<ApiResponse<SchemaContext>> getSchema() {
        SchemaContext context = schemaContextService.buildContext();
        return ResponseEntity.ok(ApiResponse.ok(context));
    }

    /** POST /api/data-copilot/sql-candidates — 生成 SQL 候选 */
    @PostMapping("/sql-candidates")
    public ResponseEntity<ApiResponse<SqlGenerationResponse>> createSqlCandidate(
            @Valid @RequestBody SqlGenerationRequest request) {
        SqlGenerationResponse response = sqlGenerationService.generate(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * POST /api/data-copilot/sql-candidates/{candidateId}/execute — 执行已确认的 SQL 候选。
     * 请求体只允许 confirmationToken，不允许传 SQL。
     */
    @PostMapping("/sql-candidates/{candidateId}/execute")
    public ResponseEntity<ApiResponse<SqlExecutionResponse>> executeSqlCandidate(
            @PathVariable("candidateId") String candidateId,
            @Valid @RequestBody SqlExecutionRequest request) {
        // 执行编排（含确认、二次 guardrails、执行、脱敏、解释、审计）统一在 QueryExecutionService
        SqlExecutionResponse response = queryExecutionService.execute(
                candidateId, request.confirmationToken());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** GET /api/data-copilot/audit-logs — 返回最近审计日志，分页 */
    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<List<QueryAuditLog>>> getAuditLogs(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        List<QueryAuditLog> logs = auditService.findRecent(page, size);
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }
}
