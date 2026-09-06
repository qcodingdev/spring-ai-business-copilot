package dev.qcoding.businesscopilot.governance;

import dev.qcoding.businesscopilot.commonsecurity.IndependentReviewService;
import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Role-aware four-eyes review API for Data and Report business objects. */
@RestController
@RequestMapping("/api/reviews")
public class WorkflowReviewController {

    private final IndependentReviewService reviewService;

    public WorkflowReviewController(IndependentReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/queue")
    public ResponseEntity<ApiResponse<?>> queue(@RequestParam IndependentReviewService.SubjectType subjectType) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.queue(subjectType)));
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<?>> mine(@RequestParam IndependentReviewService.SubjectType subjectType) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.mine(subjectType)));
    }

    @GetMapping("/subject/{subjectType}/{subjectId}")
    public ResponseEntity<ApiResponse<?>> status(
            @PathVariable IndependentReviewService.SubjectType subjectType,
            @PathVariable String subjectId) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.status(subjectType, subjectId)));
    }

    @PostMapping("/{reviewTaskId}/decision")
    public ResponseEntity<ApiResponse<?>> decide(
            @PathVariable long reviewTaskId,
            @Valid @RequestBody DecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.decide(
                reviewTaskId, request.decision(), request.note())));
    }

    public record DecisionRequest(
            @NotNull IndependentReviewService.Decision decision,
            @NotBlank @Size(max = 1000) String note) {
    }
}
