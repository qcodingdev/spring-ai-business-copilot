package dev.qcoding.businesscopilot.taskruntime;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    public TaskRunUserController(TaskRunStore store, CurrentActorProvider actorProvider) {
        this.store = store;
        this.actorProvider = actorProvider;
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<?>> myRuns(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        String actorId = actorProvider.currentActor().actorId();
        return ResponseEntity.ok(ApiResponse.ok(
                store.findByOwner(actorId, Math.max(1, Math.min(limit, 200)))));
    }
}
