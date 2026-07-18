package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;

/** 未启用所需 AI 模型时抛出，避免空指针并返回清晰错误。 */
public class AiModelNotEnabledException extends BusinessException {

    public AiModelNotEnabledException(String message) {
        super(ErrorCode.AI_MODEL_ERROR, message);
    }
}
