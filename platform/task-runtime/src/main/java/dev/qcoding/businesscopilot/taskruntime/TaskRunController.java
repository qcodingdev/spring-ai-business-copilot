package dev.qcoding.businesscopilot.taskruntime;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行时间线只读 API（RUN-06）：步骤、证据引用、失败类别、停止原因和确认人。
 *
 * <p>挂在 /api/admin/task-runs 下，仅管理员可访问（由应用安全配置约束）。
 * 响应只包含结果、证据引用和受限摘要，不包含敏感全文。</p>
 */
@RestController
@RequestMapping("/api/admin/task-runs")
public class TaskRunController {

    private final TaskRunStore store;
    private final CurrentActorProvider actorProvider;

    public TaskRunController(TaskRunStore store, CurrentActorProvider actorProvider) {
        this.store = store;
        this.actorProvider = actorProvider;
    }



    /** 按状态列出运行；不传状态时列出全部可恢复与终态之外的常见视图。 */
    @GetMapping
    public ResponseEntity<ApiResponse<?>> runs(
            @RequestParam(name = "status", required = false) TaskRunStatus status) {
        List<TaskRun> runs = status != null
                ? store.findByStatus(status)
                : store.findByStatus(TaskRunStatus.WAITING_CONFIRMATION);
        return ResponseEntity.ok(ApiResponse.ok(runs));
    }

    /** 单个运行的完整时间线（运行 + 步骤 + 尝试）。 */
    @GetMapping("/{runId}/timeline")
    public ResponseEntity<ApiResponse<?>> timeline(@PathVariable String runId) {
        TaskRun run = store.findRun(runId).orElse(null);
        if (run == null) {
            return ResponseEntity.ok(ApiResponse.fail("RUN_NOT_FOUND",
                    "运行不存在 / Run not found"));
        }
        List<TaskStep> steps = store.findSteps(runId);
        List<TaskAttempt> attempts = store.findAttempts(runId);
        return ResponseEntity.ok(ApiResponse.ok(new TaskRunService.RunTimeline(run, steps, attempts)));
    }
}
