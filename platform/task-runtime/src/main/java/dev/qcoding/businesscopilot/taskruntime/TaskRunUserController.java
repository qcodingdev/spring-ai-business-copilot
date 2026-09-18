package dev.qcoding.businesscopilot.taskruntime;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RUN-06：用户自己的运行时间线（非管理员视图）。
 *
 * <p>只返回当前操作者本人的运行记录；时间线内容仍以结果、证据引用和受限摘要为主，
 * 不包含敏感全文。管理员全量视图见 {@link TaskRunController}。</p>
 */
@RestController
@RequestMapping("/api/task-runs")
public class TaskRunUserController {

    private final TaskRunStore store;
    private final CurrentActorProvider actorProvider;
    private final TaskRunService taskRunService;

    public TaskRunUserController(TaskRunStore store, CurrentActorProvider actorProvider,
                                 TaskRunService taskRunService) {
        this.store = store;
        this.actorProvider = actorProvider;
        this.taskRunService = taskRunService;
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<?>> myRuns(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        String actorId = actorProvider.currentActor().actorId();
        return ResponseEntity.ok(ApiResponse.ok(
                store.findByOwner(actorId, Math.max(1, Math.min(limit, 200)))));
    }

    /**
     * 进程重启、人工等待和外部副作用未知时的业务恢复入口。
     * Runtime 只提供已完成/可重试步骤，不替业务模块伪造通用重试；用户必须回到原业务页核对对象状态。
     */
    @GetMapping("/mine/recovery")
    public ResponseEntity<ApiResponse<?>> recoveryPlans() {
        return ResponseEntity.ok(ApiResponse.ok(taskRunService.recoveryPlans()));
    }

    /** 当前操作者查看单个运行的恢复计划。 */
    @GetMapping("/mine/{runId}/recovery")
    public ResponseEntity<ApiResponse<?>> recoveryPlan(@PathVariable String runId) {
        return ResponseEntity.ok(ApiResponse.ok(taskRunService.recoveryPlan(runId)));
    }
}
