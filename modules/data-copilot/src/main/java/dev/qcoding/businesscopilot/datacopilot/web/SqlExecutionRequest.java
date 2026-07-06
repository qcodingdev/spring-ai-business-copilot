package dev.qcoding.businesscopilot.datacopilot.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for executing a confirmed SQL candidate.
 *
 * <p>SQL 候选执行请求。只允许传 confirmationToken，不允许传 SQL。
 * SQL 只能从服务端保存的候选中取出，确保执行的是经过 guardrails 审查的语句。</p>
 *
 * @param confirmationToken secure confirmation token returned by the generation endpoint
 */
public record SqlExecutionRequest(
        @NotBlank(message = "confirmationToken 不能为空")
        @Size(max = 128, message = "confirmationToken 长度不能超过128字符")
        String confirmationToken) {
}
