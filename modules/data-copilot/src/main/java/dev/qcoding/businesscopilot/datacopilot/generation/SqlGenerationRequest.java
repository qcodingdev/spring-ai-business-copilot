package dev.qcoding.businesscopilot.datacopilot.generation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to generate a SQL candidate from a natural language question.
 *
 * @param question the user's natural language business question
 */
public record SqlGenerationRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题长度不能超过1000字符")
        String question) {
}
