package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 普通业务用户使用的场景目录、实时执行和预生成结果接口。 */
@RestController
@RequestMapping("/api/demo")
public class DemoScenarioController {

    private static final String SAMPLE_NOTICE =
            "演示结果，不是本次实时生成；用户修改后的内容不会反映在该结果中。";
    private final DemoScenarioRepository scenarioRepository;
    private final DemoScenarioExecutionService executionService;
    private final RuntimeModeProperties runtimeModeProperties;
    private final ClientFingerprintService fingerprintService;
    private final PublicDemoQuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final DemoOverviewService overviewService;

    public DemoScenarioController(
            DemoScenarioRepository scenarioRepository,
            DemoScenarioExecutionService executionService,
            RuntimeModeProperties runtimeModeProperties,
            ClientFingerprintService fingerprintService,
            PublicDemoQuotaService quotaService,
            ObjectMapper objectMapper,
            DemoOverviewService overviewService) {
        this.scenarioRepository = scenarioRepository;
        this.executionService = executionService;
        this.runtimeModeProperties = runtimeModeProperties;
        this.fingerprintService = fingerprintService;
        this.quotaService = quotaService;
        this.objectMapper = objectMapper;
        this.overviewService = overviewService;
    }

    @GetMapping("/scenarios")
    public ResponseEntity<ApiResponse<List<DemoScenario.ScenarioProjection>>> scenarios(
            @RequestParam(required = false) String module) {
        DemoModule parsed = parseModule(module);
        List<DemoScenario.ScenarioProjection> result = scenarioRepository.findEnabled(parsed)
                .stream().map(DemoScenario::projection).toList();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/scenarios/{scenarioId}/sample-result")
    public ResponseEntity<ApiResponse<SampleResult>> sampleResult(
            @PathVariable String scenarioId) {
        DemoScenario scenario = executionService.requireScenario(scenarioId);
        DemoScenarioRepository.SampleResultRecord record =
                scenarioRepository.findSampleResult(scenario.scenarioId()).orElseThrow(() ->
                        new BusinessException(ErrorCode.DEMO_SCENARIO_NOT_AVAILABLE,
                                "当前场景没有可用的预生成示例结果。"));
        return ResponseEntity.ok(ApiResponse.ok(new SampleResult(
                "PREGENERATED", record.scenarioId(), record.scenarioVersion(),
                record.generatedAt(), readJson(record.resultJson()), SAMPLE_NOTICE)));
    }

    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<Object>> usage(HttpServletRequest request) {
        if (runtimeModeProperties.mode() != RuntimeMode.PUBLIC_DEMO) {
            return ResponseEntity.ok(ApiResponse.ok(
                    Map.of("limited", false, "runtimeMode", runtimeModeProperties.mode().propertyValue())));
        }
        String fingerprint = fingerprintService.fingerprint(request);
        return ResponseEntity.ok(ApiResponse.ok(quotaService.snapshot(fingerprint)));
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<DemoOverviewService.Overview>> overview() {
        return ResponseEntity.ok(ApiResponse.ok(overviewService.overview()));
    }

    @PostMapping("/scenarios/execute")
    public ResponseEntity<ApiResponse<ExecutionResponse>> execute(
            @Valid @RequestBody ExecutionRequest request,
            HttpServletRequest servletRequest) {
        PublicDemoQuotaService.UsageSnapshot usage = null;
        if (runtimeModeProperties.mode() == RuntimeMode.PUBLIC_DEMO) {
            String fingerprint = fingerprintService.fingerprint(servletRequest);
            usage = quotaService.consumeBusinessOperation(fingerprint);
        }
        DemoScenarioExecutionService.ExecutionResult result =
                executionService.execute(request.scenarioId(), request.userInput());
        return ResponseEntity.ok(ApiResponse.ok(new ExecutionResponse(result, usage)));
    }

    private DemoModule parseModule(String module) {
        if (module == null || module.isBlank()) return null;
        try {
            return DemoModule.valueOf(module.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未知的业务模块。");
        }
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("预生成示例结果不可用", ex);
        }
    }

    public record ExecutionRequest(
            @NotBlank(message = "场景编号不能为空。") String scenarioId,
            @NotBlank(message = "业务输入不能为空。") String userInput) {
    }

    public record ExecutionResponse(
            DemoScenarioExecutionService.ExecutionResult execution,
            PublicDemoQuotaService.UsageSnapshot usage) {
    }

    public record SampleResult(
            String source,
            String scenarioId,
            int scenarioVersion,
            java.time.Instant generatedAt,
            Object result,
            String notice) {
    }
}
