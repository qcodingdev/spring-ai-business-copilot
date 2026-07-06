package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;

/**
 * Raised when no chat model is configured (e.g. {@code spring.ai.model.chat=none}).
 *
 * <p>当未启用任何 chat model 时抛出，避免空指针并给出清晰错误。</p>
 */
public class AiModelNotEnabledException extends BusinessException {

    public AiModelNotEnabledException(String message) {
        super(ErrorCode.AI_MODEL_ERROR, message);
    }
}
